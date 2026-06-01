package net.opendasharchive.openarchive.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.opendasharchive.openarchive.BuildConfig
import net.opendasharchive.openarchive.core.logger.AppLogger
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.C2PA
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.Reader
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

object ProofmodeC2paManager {

    // Two-key design for full hardware signing:
    //
    // SIGNING_KEY_ALIAS — EC P-256 in Android Keystore TEE. This key signs media content.
    //   Key bytes never leave secure hardware. Signing is done via Signer.withCallback
    //   (called directly — KeyStoreSigner.createSigner is broken in c2pa-android 0.0.9).
    //
    // Software CA key — BouncyCastle EC P-256, encrypted at rest with Keystore-backed
    //   AES-256-GCM. Only used to sign the leaf certificate (not media content).
    //   Cert building with a Keystore key via BouncyCastle fails (BouncyCastle can't
    //   access TEE key material), so we use a software key solely for cert signing.
    //   The leaf cert carries the Keystore key's PUBLIC key, so manifest signatures
    //   are verified against the TEE key.
    private const val SIGNING_KEY_ALIAS = "openarchive_c2pa_signing_key"
    private const val CA_KEY_FILE       = "c2pa_ca_private.key"   // encrypted, software CA
    private const val CERT_CHAIN_FILE   = "c2pa_cert_chain.pem"   // public, on disk
    private const val CA_CERT_FILE      = "c2pa_ca_cert.pem"      // public, on disk
    private const val WRAP_KEY_ALIAS    = "openarchive_c2pa_wrap_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN       = 128

    @Volatile private var trustSettingsLoaded = false

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun init(context: Context) {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            trustSettingsLoaded = false
            ensureCertsExist(context)
            AppLogger.i("[C2PA] ProofmodeC2paManager initialized")
        } catch (e: Exception) {
            AppLogger.e("[C2PA] Initialization failed", e)
        }
    }

    suspend fun embedProof(
        file: File,
        mimeType: String,
        metadata: MetadataCollector.CaptureMetadata,
        context: Context,
    ): File? {
        if (!Prefs.useC2pa) return null
        if (!file.exists()) {
            AppLogger.e("[C2PA] File does not exist: ${file.absolutePath}")
            return null
        }

        return try {
            val certChainPem = loadPlain(context, CERT_CHAIN_FILE) ?: run {
                ensureCertsExist(context)
                loadPlain(context, CERT_CHAIN_FILE) ?: return null
            }
            val caCertPem = loadPlain(context, CA_CERT_FILE) ?: run {
                ensureCertsExist(context)
                loadPlain(context, CA_CERT_FILE) ?: return null
            }

            if (!trustSettingsLoaded) {
                try {
                    C2PA.loadSettings(buildTrustSettings(caCertPem), "json")
                    trustSettingsLoaded = true
                } catch (e: Exception) {
                    AppLogger.w("[C2PA] loadSettings warning: ${e.message}")
                }
            }

            val manifestJson = buildManifestJson(metadata)
            val tmp = File(file.parent, "${file.nameWithoutExtension}_c2pa_tmp.${file.extension}")
            tmp.delete()

            // Signer.withCallback called directly — bypasses the broken KeyStoreSigner.createSigner.
            // The signing callback uses the Keystore TEE key: key never enters app memory.
            Builder.fromJson(manifestJson).use { builder ->
                Signer.withCallback(SigningAlgorithm.ES256, certChainPem, null) { data ->
                    keystoreSign(data)
                }.use { signer ->
                    FileStream(file, FileStream.Mode.READ, false).use { source ->
                        FileStream(tmp, FileStream.Mode.READ_WRITE, true).use { dest ->
                            builder.sign(mimeType, source, dest, signer)
                        }
                    }
                }
            }

            val verified = try {
                FileStream(tmp, FileStream.Mode.READ, false).use { s ->
                    Reader.fromStream(mimeType, s).use { it.isEmbedded() }
                }
            } catch (e: Exception) {
                AppLogger.e("[C2PA] Post-sign verification threw: ${e.message}")
                tmp.delete()
                false
            }
            if (!verified) {
                AppLogger.e("[C2PA] Post-sign verification failed — original preserved: ${file.name}")
                tmp.delete()
                return null
            }

            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }

            AppLogger.i("[C2PA] Manifest embedded + verified (TEE key): ${file.name} (${file.length() / 1024} KB)")
            file
        } catch (e: Exception) {
            AppLogger.e("[C2PA] Failed to embed proof in ${file.name}", e)
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Key and cert management
    // ---------------------------------------------------------------------------

    private fun ensureCertsExist(context: Context) {
        val caKeyFile   = File(context.filesDir, CA_KEY_FILE)
        val chainFile   = File(context.filesDir, CERT_CHAIN_FILE)
        val caFile      = File(context.filesDir, CA_CERT_FILE)
        val signingKeyExists = keystoreKeyExists()

        if (signingKeyExists && caKeyFile.exists() && chainFile.exists() && caFile.exists()) {
            if (readEncrypted(caKeyFile) != null && certMatchesKeystoreKey(chainFile)) return
            AppLogger.w("[C2PA] Wrap key lost or cert/key mismatch — wiping files and regenerating")
        }
        // Wipe any partial state
        listOf(caKeyFile, chainFile, caFile).forEach { it.delete() }
        if (!signingKeyExists) generateKeystoreSigningKey()

        AppLogger.i("[C2PA] Generating software CA key + cert chain (Keystore pubkey in leaf)")

        // Software CA key — only used to sign certificates, not content
        val caKpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        caKpg.initialize(ECNamedCurveGenParameterSpec("secp256r1"))
        val caKeyPair = caKpg.generateKeyPair()

        // Leaf cert carries the TEE key's PUBLIC KEY — manifest signatures verify against it
        val keystorePublicKey = getKeystorePublicKey()
            ?: throw IllegalStateException("Cannot read Keystore public key")

        val caCert   = buildCaCert(caKeyPair.private, caKeyPair.public)
        val leafCert = buildLeafCert(caKeyPair.private, keystorePublicKey, caCert)

        val chainPem     = toPem("CERTIFICATE", leafCert.encoded) + toPem("CERTIFICATE", caCert.encoded)
        val caCertPem    = toPem("CERTIFICATE", caCert.encoded)
        val caPrivKeyPem = toPem("PRIVATE KEY", caKeyPair.private.encoded)

        val tmpCaKey = File(context.filesDir, "$CA_KEY_FILE.tmp")
        val tmpChain = File(context.filesDir, "$CERT_CHAIN_FILE.tmp")
        val tmpCa    = File(context.filesDir, "$CA_CERT_FILE.tmp")
        try {
            writeEncrypted(tmpCaKey, caPrivKeyPem.toByteArray(Charsets.UTF_8))
            tmpChain.writeText(chainPem)
            tmpCa.writeText(caCertPem)
            tmpCaKey.renameTo(caKeyFile)
            tmpChain.renameTo(chainFile)
            tmpCa.renameTo(caFile)
        } catch (e: Exception) {
            tmpCaKey.delete(); tmpChain.delete(); tmpCa.delete(); throw e
        }

        trustSettingsLoaded = false
        AppLogger.i("[C2PA] Keystore TEE signing key + cert chain ready")
    }

    private fun generateKeystoreSigningKey() {
        val spec = KeyGenParameterSpec.Builder(SIGNING_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            .also { it.initialize(spec) }
            .generateKeyPair()
        AppLogger.i("[C2PA] Keystore EC signing key generated")
    }

    private fun keystoreKeyExists(): Boolean = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        ks.containsAlias(SIGNING_KEY_ALIAS)
    }.getOrDefault(false)

    private fun getKeystorePublicKey(): PublicKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        ks.getCertificate(SIGNING_KEY_ALIAS)?.publicKey
    }.getOrNull()

    /**
     * Parses the first cert in the on-disk chain file and checks its public key
     * matches the current Keystore signing key. Guards against stale cert files
     * left over from a previous key generation (e.g. after app update or key loss).
     */
    private fun certMatchesKeystoreKey(chainFile: File): Boolean = runCatching {
        val keystorePubKey = getKeystorePublicKey() ?: return false
        val pem = chainFile.readText()
        val certBytes = java.util.Base64.getDecoder().decode(
            pem.lines()
                .filter { !it.startsWith("-----") && it.isNotBlank() }
                .joinToString("")
                .takeWhile { it != '-' } // stop at second cert boundary
                .let {
                    // Extract only the first cert's base64 block
                    pem.substringAfter("-----BEGIN CERTIFICATE-----\n")
                        .substringBefore("\n-----END CERTIFICATE-----")
                        .replace("\n", "")
                }
        )
        val leafCert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(certBytes.inputStream()) as java.security.cert.X509Certificate
        leafCert.publicKey.encoded.contentEquals(keystorePubKey.encoded)
    }.getOrElse { AppLogger.w("[C2PA] Cert/key match check failed: ${it.message}"); false }

    /** Signs data inside the TEE — Keystore private key never leaves secure hardware. */
    private fun keystoreSign(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val privateKey = ks.getKey(SIGNING_KEY_ALIAS, null) as PrivateKey
        // Explicit provider ensures dispatch to Android Keystore on all OEM builds
        return Signature.getInstance("SHA256withECDSA", "AndroidKeyStoreBCWorkaround").apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    // ---------------------------------------------------------------------------
    // AES-256-GCM wrap for software CA private key file
    // ---------------------------------------------------------------------------

    private fun ensureWrapKey() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(WRAP_KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .also { it.init(spec) }
            .generateKey()
    }

    private fun writeEncrypted(file: File, plaintext: ByteArray) {
        ensureWrapKey()
        val ks  = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val key = ks.getKey(WRAP_KEY_ALIAS, null) as javax.crypto.SecretKey
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv; val ct = cipher.doFinal(plaintext)
        file.outputStream().use { it.write(iv.size.to4Bytes()); it.write(iv); it.write(ct) }
    }

    private fun readEncrypted(file: File): ByteArray? = runCatching {
        ensureWrapKey()
        val ks  = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val key = ks.getKey(WRAP_KEY_ALIAS, null) as javax.crypto.SecretKey
        val b   = file.readBytes()
        val ivLen = b.sliceArray(0..3).from4Bytes()
        val iv    = b.sliceArray(4 until 4 + ivLen)
        val ct    = b.sliceArray(4 + ivLen until b.size)
        Cipher.getInstance(AES_GCM_TRANSFORM)
            .also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv)) }
            .doFinal(ct)
    }.getOrElse { AppLogger.e("[C2PA] Failed to decrypt CA key file", it); null }

    private fun loadPlain(context: Context, name: String): String? {
        val f = File(context.filesDir, name)
        return if (f.exists()) f.readText() else null
    }

    private fun Int.to4Bytes(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte()
    )
    private fun ByteArray.from4Bytes(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8)  or  (this[3].toInt() and 0xFF)

    // ---------------------------------------------------------------------------
    // Certificate building — software CA key signs certs (works; no Keystore key used here)
    // ---------------------------------------------------------------------------

    private fun buildCaCert(caPrivateKey: PrivateKey, caPublicKey: PublicKey): X509Certificate {
        val subject   = X500Name("CN=OpenArchive Save Root CA, O=OpenArchive, C=US")
        val serial    = BigInteger(64, SecureRandom())
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter  = Date(System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000)
        val builder   = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, caPublicKey)
        val ski = computeSki(SubjectPublicKeyInfo.getInstance(caPublicKey.encoded))
        builder.addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(ski))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature))
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(BcContentSigner(caPrivateKey, "SHA256withECDSA")))
    }

    /**
     * Leaf cert whose SubjectPublicKeyInfo carries [subjectPublicKey] (the Keystore TEE key).
     * Signed by [caPrivateKey] (software CA key — BouncyCastle handles this correctly).
     * Manifest signatures are made with the Keystore key and verified against this cert.
     */
    private fun buildLeafCert(
        caPrivateKey: PrivateKey,
        subjectPublicKey: PublicKey,
        caCert: X509Certificate,
    ): X509Certificate {
        val subject   = X500Name("CN=OpenArchive Save, O=OpenArchive, C=US")
        val issuer    = X500Name("CN=OpenArchive Save Root CA, O=OpenArchive, C=US")
        val serial    = BigInteger(64, SecureRandom())
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter  = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
        val builder   = JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, subjectPublicKey)
        val ski   = computeSki(SubjectPublicKeyInfo.getInstance(subjectPublicKey.encoded))
        val caSki = computeSki(SubjectPublicKeyInfo.getInstance(caCert.publicKey.encoded))
        builder.addExtension(Extension.subjectKeyIdentifier,   false, SubjectKeyIdentifier(ski))
        builder.addExtension(Extension.authorityKeyIdentifier, false, AuthorityKeyIdentifier(caSki))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
        // id-kp-documentSigning (RFC 9336)
        builder.addExtension(Extension.extendedKeyUsage, true,
            ExtendedKeyUsage(KeyPurposeId.getInstance(ASN1ObjectIdentifier("1.3.6.1.5.5.7.3.36"))))
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(BcContentSigner(caPrivateKey, "SHA256withECDSA")))
    }

    private fun computeSki(spki: SubjectPublicKeyInfo): ByteArray {
        val d = SHA1Digest(); val r = ByteArray(d.digestSize)
        val b = spki.publicKeyData.bytes
        d.update(b, 0, b.size); d.doFinal(r, 0); return r
    }

    private fun toPem(type: String, encoded: ByteArray): String {
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(PemObject(type, encoded)) }
        return sw.toString()
    }

    private class BcContentSigner(
        private val privateKey: PrivateKey,
        private val algorithm: String,
    ) : ContentSigner {
        private val sig = Signature.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME)
            .also { it.initSign(privateKey) }
        private val buf = ByteArrayOutputStream()
        override fun getAlgorithmIdentifier() =
            AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.4.3.2"))
        override fun getOutputStream(): java.io.OutputStream = buf
        override fun getSignature(): ByteArray { sig.update(buf.toByteArray()); return sig.sign() }
    }

    // ---------------------------------------------------------------------------
    // Trust settings + manifest JSON
    // ---------------------------------------------------------------------------

    private fun buildTrustSettings(caCertPem: String) =
        """{"trust":{"trust_anchors":"${caCertPem.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")}"}}"""

    private fun buildManifestJson(m: MetadataCollector.CaptureMetadata): String {
        val captureIso    = isoFormat.format(Date(m.captureTime))
        val claimGenerator = "OpenArchive Save/${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        val assertions = buildList {
            add(actionsAssertion())
            add(creativeWorkAssertion(m, captureIso))
            add(deviceAssertion(m))
            if (m.latitude != null && m.longitude != null) add(locationAssertion(m))
            if (m.networkType != null || m.ipv4 != null || m.ipv6 != null || m.cellInfo != null) add(networkAssertion(m))
        }.joinToString(",\n")
        return """
        {
          "claim_generator": ${claimGenerator.toJsonString()},
          "assertions": [
            $assertions
          ]
        }
        """.trimIndent()
    }

    private fun actionsAssertion() = """
        {"label":"c2pa.actions","data":{"actions":[{"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"}]}}
    """.trimIndent()

    private fun creativeWorkAssertion(m: MetadataCollector.CaptureMetadata, captureIso: String): String {
        val locBlock = if (m.latitude != null && m.longitude != null) buildString {
            append(""","locationCreated":{"@type":"Place","geo":{"@type":"GeoCoordinates","latitude":${m.latitude},"longitude":${m.longitude}""")
            if (m.locationAltitude != null) append(""","elevation":${m.locationAltitude}""")
            append("}}")
        } else ""
        return """
        {"label":"stds.schema-org.CreativeWork","data":{"@context":"https://schema.org","@type":"CreativeWork","author":[{"@type":"SoftwareApplication","name":"OpenArchive Save","version":${BuildConfig.VERSION_NAME.toJsonString()},"buildNumber":"${BuildConfig.VERSION_CODE}"}],"dateCreated":"$captureIso"$locBlock}}
        """.trimIndent()
    }

    private fun deviceAssertion(m: MetadataCollector.CaptureMetadata) = """
        {"label":"org.openarchive.save.device","data":{"make":${m.deviceMake.toJsonString()},"model":${m.deviceModel.toJsonString()},"brand":${m.deviceBrand.toJsonString()},"hardware":${m.hardware.toJsonString()},"locale":${m.locale.toJsonString()},"language":${m.language.toJsonString()}${if (m.screenSizeInches != null) ""","screenSizeInches":${m.screenSizeInches}""" else ""}}}
    """.trimIndent()

    private fun locationAssertion(m: MetadataCollector.CaptureMetadata): String {
        val fields = buildString {
            append(""""latitude":${m.latitude}""")
            append(""","longitude":${m.longitude}""")
            if (m.locationAltitude != null) append(""","altitude":${m.locationAltitude}""")
            if (m.locationAccuracy != null) append(""","accuracy":${m.locationAccuracy}""")
            if (m.locationBearing != null)  append(""","bearing":${m.locationBearing}""")
            if (m.locationSpeed != null)    append(""","speed":${m.locationSpeed}""")
            if (m.locationProvider != null) append(""","provider":${m.locationProvider.toJsonString()}""")
            if (m.locationTime != null)     append(""","timestamp":${isoFormat.format(Date(m.locationTime)).toJsonString()}""")
        }
        return """{"label":"org.openarchive.save.location","data":{$fields}}"""
    }

    private fun networkAssertion(m: MetadataCollector.CaptureMetadata): String {
        val fields = buildList {
            if (m.networkType != null) add(""""networkType":${m.networkType.toJsonString()}""")
            if (m.ipv4 != null)        add(""""ipv4":${m.ipv4.toJsonString()}""")
            if (m.ipv6 != null)        add(""""ipv6":${m.ipv6.toJsonString()}""")
            if (m.cellInfo != null)    add(""""cellInfo":${m.cellInfo}""")
        }.joinToString(",")
        return """{"label":"org.openarchive.save.network","data":{$fields}}"""
    }

    private fun String.toJsonString(): String {
        val e = replace("\\","\\\\").replace("\"","\\\"")
            .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")
        return "\"$e\""
    }
}
