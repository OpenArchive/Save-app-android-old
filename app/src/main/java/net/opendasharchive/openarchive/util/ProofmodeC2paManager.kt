package net.opendasharchive.openarchive.util

import android.content.Context
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
import org.contentauth.c2pa.ByteArrayStream
import org.contentauth.c2pa.C2PA
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ProofmodeC2paManager {

    private const val KEY_FILE = "c2pa_private.key"
    private const val CERT_CHAIN_FILE = "c2pa_cert_chain.pem"
    private const val CA_CERT_FILE = "c2pa_ca_cert.pem"

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun init(context: Context) {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
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
            val privateKeyPem = loadFile(context, KEY_FILE) ?: run {
                ensureCertsExist(context)
                loadFile(context, KEY_FILE) ?: return null
            }
            val certChainPem = loadFile(context, CERT_CHAIN_FILE) ?: run {
                ensureCertsExist(context)
                loadFile(context, CERT_CHAIN_FILE) ?: return null
            }
            val caCertPem = loadFile(context, CA_CERT_FILE) ?: return null

            // Register our root CA as a trust anchor so c2pa-rs accepts our self-signed chain
            val trustSettings = buildTrustSettings(caCertPem)
            try {
                C2PA.loadSettings(trustSettings, "json")
            } catch (e: Exception) {
                AppLogger.w("[C2PA] loadSettings warning: ${e.message}")
            }

            val manifestJson = buildManifestJson(metadata)

            val signedBytes = Builder.fromJson(manifestJson).use { builder ->
                Signer.fromKeys(certChainPem, privateKeyPem, SigningAlgorithm.ES256).use { signer ->
                    DataStream(file.readBytes()).use { source ->
                        ByteArrayStream().use { dest ->
                            builder.sign(mimeType, source, dest, signer)
                            dest.getData()
                        }
                    }
                }
            }

            val tmp = File(file.parent, "${file.nameWithoutExtension}_c2pa_tmp.${file.extension}")
            tmp.writeBytes(signedBytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(signedBytes)
                tmp.delete()
            }

            AppLogger.i("[C2PA] Manifest embedded: ${file.name} (${signedBytes.size / 1024} KB)")
            file
        } catch (e: Exception) {
            AppLogger.e("[C2PA] Failed to embed proof in ${file.name}", e)
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Key + cert chain generation (mini CA → leaf, all in software via BouncyCastle)
    // ---------------------------------------------------------------------------

    private fun ensureCertsExist(context: Context) {
        val keyFile = File(context.filesDir, KEY_FILE)
        val chainFile = File(context.filesDir, CERT_CHAIN_FILE)
        val caFile = File(context.filesDir, CA_CERT_FILE)
        if (keyFile.exists() && chainFile.exists() && caFile.exists()) return

        AppLogger.i("[C2PA] Generating EC key pair and self-signed cert chain")

        val kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(ECNamedCurveGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val caCert = buildCaCert(keyPair.private, keyPair.public)
        val leafCert = buildLeafCert(keyPair.private, keyPair.public, caCert)

        // Chain = leaf first, then CA
        val chainPem = toPem("CERTIFICATE", leafCert.encoded) + toPem("CERTIFICATE", caCert.encoded)
        val caCertPem = toPem("CERTIFICATE", caCert.encoded)
        val privateKeyPem = toPem("PRIVATE KEY", keyPair.private.encoded)

        keyFile.writeText(privateKeyPem)
        chainFile.writeText(chainPem)
        caFile.writeText(caCertPem)

        AppLogger.i("[C2PA] Key + cert chain written to internal storage")
    }

    /** Self-signed root CA cert (CA:TRUE) — used only as trust anchor */
    private fun buildCaCert(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        val subject = X500Name("CN=OpenArchive Save Root CA, O=OpenArchive, C=US")
        val serial = BigInteger.valueOf(1)
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, publicKey)

        val spki = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val ski = computeSki(spki)
        builder.addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(ski))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )

        val signer = BcContentSigner(privateKey, "SHA256withECDSA")
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder)
    }

    /** Leaf signing cert (CA:FALSE) with extensions matching c2pa-rs requirements */
    private fun buildLeafCert(privateKey: PrivateKey, publicKey: PublicKey, caCert: X509Certificate): X509Certificate {
        val subject = X500Name("CN=OpenArchive Save, O=OpenArchive, C=US")
        val issuer = X500Name("CN=OpenArchive Save Root CA, O=OpenArchive, C=US")
        val serial = BigInteger.valueOf(2)
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, publicKey)

        val spki = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val ski = computeSki(spki)
        val caSki = computeSki(SubjectPublicKeyInfo.getInstance(caCert.publicKey.encoded))

        builder.addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(ski))
        builder.addExtension(Extension.authorityKeyIdentifier, false, AuthorityKeyIdentifier(caSki))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation)
        )
        builder.addExtension(
            Extension.extendedKeyUsage, true,
            ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection)
        )

        val signer = BcContentSigner(privateKey, "SHA256withECDSA")
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder)
    }

    /** Compute SHA-1 of SubjectPublicKeyInfo bit string (standard SKI method) */
    private fun computeSki(spki: SubjectPublicKeyInfo): ByteArray {
        val keyBytes = spki.publicKeyData.bytes
        val digest = SHA1Digest()
        val result = ByteArray(digest.digestSize)
        digest.update(keyBytes, 0, keyBytes.size)
        digest.doFinal(result, 0)
        return result
    }

    private fun toPem(type: String, encoded: ByteArray): String {
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(PemObject(type, encoded)) }
        return sw.toString()
    }

    private fun loadFile(context: Context, name: String): String? {
        val f = File(context.filesDir, name)
        return if (f.exists()) f.readText() else null
    }

    // ---------------------------------------------------------------------------
    // BouncyCastle ContentSigner backed by software private key
    // ---------------------------------------------------------------------------

    private class BcContentSigner(
        private val privateKey: PrivateKey,
        private val algorithm: String,
    ) : ContentSigner {

        private val sig = Signature.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME)
            .also { it.initSign(privateKey) }
        private val buf = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.4.3.2"))

        override fun getOutputStream(): java.io.OutputStream = buf

        override fun getSignature(): ByteArray {
            sig.update(buf.toByteArray())
            return sig.sign()
        }
    }

    // ---------------------------------------------------------------------------
    // Trust settings JSON for c2pa-rs
    // ---------------------------------------------------------------------------

    private fun buildTrustSettings(caCertPem: String): String {
        val escaped = caCertPem
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return """{"trust":{"trust_anchors":"$escaped"}}"""
    }

    // ---------------------------------------------------------------------------
    // Manifest JSON
    // ---------------------------------------------------------------------------

    private fun buildManifestJson(m: MetadataCollector.CaptureMetadata): String {
        val captureIso = isoFormat.format(Date(m.captureTime))
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

    private fun actionsAssertion(): String = """
        {
          "label": "c2pa.actions",
          "data": {
            "actions": [{
              "action": "c2pa.created",
              "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"
            }]
          }
        }
    """.trimIndent()

    private fun creativeWorkAssertion(m: MetadataCollector.CaptureMetadata, captureIso: String): String {
        val locationBlock = if (m.latitude != null && m.longitude != null) {
            buildString {
                append(""","locationCreated":{"@type":"Place","geo":{"@type":"GeoCoordinates","latitude":${m.latitude},"longitude":${m.longitude}""")
                if (m.locationAltitude != null) append(""","elevation":${m.locationAltitude}""")
                append("}}")
            }
        } else ""

        return """
        {
          "label": "stds.schema-org.CreativeWork",
          "data": {
            "@context": "https://schema.org",
            "@type": "CreativeWork",
            "author": [{
              "@type": "SoftwareApplication",
              "name": "OpenArchive Save",
              "version": ${BuildConfig.VERSION_NAME.toJsonString()},
              "buildNumber": "${BuildConfig.VERSION_CODE}"
            }],
            "dateCreated": "$captureIso"
            $locationBlock
          }
        }
        """.trimIndent()
    }

    private fun deviceAssertion(m: MetadataCollector.CaptureMetadata): String = """
        {
          "label": "org.openarchive.save.device",
          "data": {
            "make": ${m.deviceMake.toJsonString()},
            "model": ${m.deviceModel.toJsonString()},
            "brand": ${m.deviceBrand.toJsonString()},
            "hardware": ${m.hardware.toJsonString()},
            "locale": ${m.locale.toJsonString()},
            "language": ${m.language.toJsonString()}
            ${if (m.screenSizeInches != null) ""","screenSizeInches": ${m.screenSizeInches}""" else ""}
          }
        }
    """.trimIndent()

    private fun locationAssertion(m: MetadataCollector.CaptureMetadata): String {
        val fields = buildString {
            append(""""latitude": ${m.latitude}""")
            append(""","longitude": ${m.longitude}""")
            if (m.locationAltitude != null) append(""","altitude": ${m.locationAltitude}""")
            if (m.locationAccuracy != null) append(""","accuracy": ${m.locationAccuracy}""")
            if (m.locationBearing != null) append(""","bearing": ${m.locationBearing}""")
            if (m.locationSpeed != null) append(""","speed": ${m.locationSpeed}""")
            if (m.locationProvider != null) append(""","provider": ${m.locationProvider.toJsonString()}""")
            if (m.locationTime != null) append(""","timestamp": ${m.locationTime}""")
        }
        return """{"label":"org.openarchive.save.location","data":{$fields}}"""
    }

    private fun networkAssertion(m: MetadataCollector.CaptureMetadata): String {
        val fields = buildString {
            if (m.networkType != null) append(""""networkType": ${m.networkType.toJsonString()}""")
            if (m.ipv4 != null) append(""","ipv4": ${m.ipv4.toJsonString()}""")
            if (m.ipv6 != null) append(""","ipv6": ${m.ipv6.toJsonString()}""")
            if (m.cellInfo != null) append(""","cellInfo": ${m.cellInfo}""")
        }
        return """{"label":"org.openarchive.save.network","data":{$fields}}"""
    }

    private fun String.toJsonString(): String {
        val escaped = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
