package net.opendasharchive.openarchive.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.exifinterface.media.ExifInterface
import net.opendasharchive.openarchive.BuildConfig
import net.opendasharchive.openarchive.core.logger.AppLogger
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

object ProofCompanionGenerator {

    private const val SECRET_KEY_FILE = "proof_pgp_secret.bpg"
    private const val PUBLIC_KEY_FILE = "pubkey.asc"
    private const val PENDING_OTS_FILE = "pending_ots.txt"
    private const val OTS_QUEUE_MAX = 500
    private const val PGP_IDENTITY = "OpenArchive Save <proof@openarchive.app>"
    private const val OTS_CALENDAR = "https://a.pool.opentimestamps.org/timestamp"
    private const val OTS_TIMEOUT_MS = 15_000

    // PGP BouncyCastle API requires a passphrase for its internal AES layer.
    // The secret key ring file itself is additionally encrypted at rest via a
    // Keystore-backed AES-256-GCM key (see writeEncryptedKey / readEncryptedKey).
    private val PASSPHRASE = charArrayOf()

    private const val KEYSTORE_ALIAS = "openarchive_pgp_wrap_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN = 128

    // DateTimeFormatter is immutable + thread-safe — safe for concurrent capture coroutines
    private val isoFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun init(context: Context) {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            ensureKeyExists(context)
            AppLogger.i("[Proof] ProofCompanionGenerator initialized")
        } catch (e: Exception) {
            AppLogger.e("[Proof] Init failed", e)
        }
    }

    /**
     * Called at capture time (no network). Generates proof.json and PGP signatures
     * immediately after C2PA embedding while the file bytes are known-final.
     * This ensures PGP signatures are provably of the same bytes the SHA-256 hash covers.
     */
    suspend fun generateLocalProof(context: Context, evidenceFile: File, mediaHash: String) {
        if (!Prefs.useC2pa) return
        withContext(Dispatchers.IO) {
            try {
                val outDir = File(context.filesDir, "proof_companions/$mediaHash").also { it.mkdirs() }

                val proofJson = buildProofJson(evidenceFile, mediaHash)
                File(outDir, "$mediaHash.proof.json").writeText(proofJson)

                pgpSignStream(context, evidenceFile, File(outDir, "$mediaHash.asc"))
                pgpSign(context, proofJson.toByteArray(Charsets.UTF_8), File(outDir, "$mediaHash.proof.json.asc"))

                AppLogger.i("[Proof] Local proof generated at capture time for $mediaHash")
            } catch (e: Exception) {
                AppLogger.e("[Proof] Local proof generation failed for $mediaHash", e)
            }
        }
    }

    /**
     * Called at upload time. Submits OTS (network), falls back to generating proof files
     * if generateLocalProof was not called at capture, then returns all files for upload.
     */
    suspend fun prepareForUpload(context: Context, evidenceFile: File, mediaHash: String): List<File> = withContext(Dispatchers.IO) {
        if (!Prefs.useC2pa) return@withContext emptyList()
        try {
            val outDir = File(context.filesDir, "proof_companions/$mediaHash").also { it.mkdirs() }
            val proofJsonFile = File(outDir, "$mediaHash.proof.json")
            val mediaSigFile  = File(outDir, "$mediaHash.asc")
            val jsonSigFile   = File(outDir, "$mediaHash.proof.json.asc")

            // Defensive fallback — should not occur for camera captures but handles edge cases
            if (!proofJsonFile.exists() || !mediaSigFile.exists() || !jsonSigFile.exists()) {
                AppLogger.w("[Proof] Local proof missing for $mediaHash — generating at upload time")
                val proofJson = buildProofJson(evidenceFile, mediaHash)
                proofJsonFile.writeText(proofJson)
                pgpSignStream(context, evidenceFile, mediaSigFile)
                pgpSign(context, proofJson.toByteArray(Charsets.UTF_8), jsonSigFile)
            }

            // OTS is network-only — submitted at upload time with retry queue
            val otsFile = File(outDir, "$mediaHash.ots")
            submitOts(context, mediaHash, otsFile)

            val pubKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)
            buildList {
                add(proofJsonFile)
                add(mediaSigFile)
                add(jsonSigFile)
                if (pubKeyFile.exists()) add(pubKeyFile)
                if (otsFile.exists()) add(otsFile)
            }.also {
                AppLogger.i("[Proof] ${it.size} companion files ready for upload: $mediaHash")
            }
        } catch (e: Exception) {
            AppLogger.e("[Proof] Upload prep failed for $mediaHash", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------
    // Proof JSON — ProofMode v1 field names
    // ---------------------------------------------------------------------------

    private fun buildProofJson(file: File, hash: String): String {
        val now = isoFmt.format(Instant.now())

        // Read capture time from EXIF TAG_DATETIME — written at capture time by MetadataCollector,
        // guaranteed consistent with the C2PA manifest timestamp and the EXIF in the signed file.
        // EXIF datetime is in UTC (MetadataCollector writes it that way).
        val exifDtFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val created = try {
            ExifInterface(file.absolutePath)
                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { raw ->
                    val ldt = java.time.LocalDateTime.parse(raw, exifDtFormat)
                    isoFmt.format(ldt.toInstant(ZoneOffset.UTC))
                }
                ?: isoFmt.format(Instant.ofEpochMilli(file.lastModified()))
        } catch (_: Exception) {
            isoFmt.format(Instant.ofEpochMilli(file.lastModified()))
        }

        val fields = linkedMapOf<String, String?>()
        fields["File Hash SHA256"] = hash
        fields["File Path"] = file.absolutePath
        fields["File Created"] = created
        fields["File Modified"] = created
        fields["Proof Generated"] = now
        fields["Notes"] = "OpenArchive Save ${BuildConfig.VERSION_NAME}"
        fields["Manufacturer"] = Build.MANUFACTURER
        fields["Hardware"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        fields["Locale"] = Locale.getDefault().country
        fields["Language"] = Locale.getDefault().displayLanguage

        // Read GPS + device info from EXIF written at capture time
        try {
            val exif = ExifInterface(file.absolutePath)
            val latLon = exif.latLong
            if (latLon != null) {
                fields["Location.Latitude"] = latLon[0].toString()
                fields["Location.Longitude"] = latLon[1].toString()
            }
            exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)
                ?.let { fields["Location.Altitude"] = it }
            exif.getAttribute(ExifInterface.TAG_GPS_SPEED)
                ?.let { fields["Location.Speed"] = it }
            exif.getAttribute(ExifInterface.TAG_GPS_TRACK)
                ?.let { fields["Location.Bearing"] = it }
            exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD)
                ?.removePrefix("charset=Ascii ")?.lowercase()
                ?.let { fields["Location.Provider"] = it }
        } catch (_: Exception) {}

        val sb = StringBuilder("{\n")
        val entries = fields.entries.filter { it.value != null }.toList()
        entries.forEachIndexed { i, (k, v) ->
            sb.append("  ").append(jsonString(k)).append(": ").append(jsonString(v!!))
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("}")
        return sb.toString()
    }

    // ---------------------------------------------------------------------------
    // PGP key generation and detached signing
    // ---------------------------------------------------------------------------

    private fun ensureKeyExists(context: Context) {
        val secretFile = File(context.filesDir, SECRET_KEY_FILE)
        val publicFile = File(context.filesDir, PUBLIC_KEY_FILE)
        if (secretFile.exists() && publicFile.exists()) return

        AppLogger.i("[Proof] Generating Ed25519 PGP key pair")

        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(256, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val digestCalc = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
            .get(HashAlgorithmTags.SHA1)

        val pgpKeyPair = JcaPGPKeyPair(PGPPublicKey.EDDSA, keyPair, Date())

        val secretKeyEncryptor = JcePBESecretKeyEncryptorBuilder(
            PGPEncryptedData.AES_256, digestCalc
        ).setProvider(BouncyCastleProvider.PROVIDER_NAME).build(PASSPHRASE)

        val signerBuilder = JcaPGPContentSignerBuilder(
            pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA256
        ).setProvider(BouncyCastleProvider.PROVIDER_NAME)

        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            PGP_IDENTITY,
            digestCalc,
            null,
            null,
            signerBuilder,
            secretKeyEncryptor
        )

        // Write secret key ring — encrypted with Keystore-backed AES-256-GCM
        val secretBytes = ByteArrayOutputStream()
            .also { keyRingGen.generateSecretKeyRing().encode(it) }.toByteArray()
        writeEncryptedKey(secretFile, secretBytes)

        // Write public key in ASCII armor
        publicFile.outputStream().use { out ->
            ArmoredOutputStream(out).use { armoredOut ->
                keyRingGen.generatePublicKeyRing().encode(armoredOut)
            }
        }
        AppLogger.i("[Proof] PGP key pair written")
    }

    private fun pgpSign(context: Context, data: ByteArray, outFile: File) {
        val sigGen = initPgpSigGen(context) ?: return
        sigGen.update(data)
        writePgpSig(sigGen, outFile)
    }

    private fun pgpSignStream(context: Context, sourceFile: File, outFile: File) {
        val sigGen = initPgpSigGen(context) ?: return
        sourceFile.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) sigGen.update(buf, 0, n)
        }
        writePgpSig(sigGen, outFile)
    }

    private fun initPgpSigGen(context: Context): PGPSignatureGenerator? {
        val secretFile = File(context.filesDir, SECRET_KEY_FILE)
        if (!secretFile.exists()) {
            ensureKeyExists(context)
            if (!secretFile.exists()) return null
        }
        val secretBytes = readEncryptedKey(secretFile) ?: run {
            // Keystore wrap key lost — wipe and regenerate both PGP key files
            AppLogger.w("[Proof] Wrap key lost — wiping stale PGP key files and regenerating")
            File(context.filesDir, SECRET_KEY_FILE).delete()
            File(context.filesDir, PUBLIC_KEY_FILE).delete()
            ensureKeyExists(context)
            readEncryptedKey(File(context.filesDir, SECRET_KEY_FILE)) ?: run {
                AppLogger.e("[Proof] Failed to decrypt PGP secret key after regeneration")
                return null
            }
        }
        val secretKeyRing = PGPSecretKeyRing(
            PGPUtil.getDecoderStream(secretBytes.inputStream()),
            JcaKeyFingerprintCalculator()
        )
        val secretKey = secretKeyRing.secretKey
        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(PASSPHRASE)
        )
        return PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        ).also { it.init(PGPSignature.BINARY_DOCUMENT, privateKey) }
    }

    private fun writePgpSig(sigGen: PGPSignatureGenerator, outFile: File) {
        ByteArrayOutputStream().use { baos ->
            ArmoredOutputStream(baos).use { armoredOut ->
                BCPGOutputStream(armoredOut).use { bcpgOut ->
                    sigGen.generate().encode(bcpgOut)
                }
            }
            outFile.writeBytes(baos.toByteArray())
        }
    }

    // ---------------------------------------------------------------------------
    // OpenTimestamps — submit SHA256 hash to OTS calendar with retry queue
    // ---------------------------------------------------------------------------

    private fun submitOts(context: Context, hexHash: String, outFile: File) {
        // Drain any previously failed hashes before submitting the new one
        drainPendingOts(context)

        if (!postOts(hexHash, outFile)) {
            // Network unavailable — queue for next upload
            enqueuePendingOts(context, hexHash)
            AppLogger.w("[OTS] Queued $hexHash for retry")
        }
    }

    private fun drainPendingOts(context: Context) {
        val queue = File(context.filesDir, PENDING_OTS_FILE)
        if (!queue.exists()) return
        val remaining = mutableListOf<String>()
        queue.readLines().filter { it.isNotBlank() }.forEach { hash ->
            val companionDir = File(context.filesDir, "proof_companions/$hash")
            // Companion dir deleted after upload — nothing left to anchor, drop from queue
            if (!companionDir.exists()) return@forEach
            val otsFile = File(companionDir, "$hash.ots")
            if (otsFile.exists()) return@forEach  // already succeeded
            if (!postOts(hash, otsFile)) remaining.add(hash)
        }
        if (remaining.isEmpty()) queue.delete() else queue.writeText(remaining.joinToString("\n"))
    }

    @Synchronized
    private fun enqueuePendingOts(context: Context, hexHash: String) {
        val queue = File(context.filesDir, PENDING_OTS_FILE)
        val existing = if (queue.exists()) queue.readLines().filter { it.isNotBlank() } else emptyList()
        if (hexHash in existing) return  // already queued — no duplicate entries
        val updated = (existing + hexHash).let {
            if (it.size > OTS_QUEUE_MAX) it.drop(it.size - OTS_QUEUE_MAX) else it  // bound size, drop oldest
        }
        queue.writeText(updated.joinToString("\n") + "\n")
    }

    private fun postOts(hexHash: String, outFile: File): Boolean {
        return try {
            val hashBytes = hexToBytes(hexHash)
            val conn = (URL(OTS_CALENDAR).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = OTS_TIMEOUT_MS
                readTimeout = OTS_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/octet-stream")
            }
            conn.outputStream.use { it.write(hashBytes) }
            val success = conn.responseCode == 200
            if (success) {
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(conn.inputStream.use { it.readBytes() })
                AppLogger.i("[OTS] Timestamp received for $hexHash")
            } else {
                AppLogger.w("[OTS] Calendar returned HTTP ${conn.responseCode}")
            }
            conn.disconnect()
            success
        } catch (e: Exception) {
            AppLogger.w("[OTS] Submission failed: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------------------
    // AES-256-GCM key wrapping for PGP secret key ring
    // ---------------------------------------------------------------------------

    private fun ensureWrapKey() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
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

    private fun writeEncryptedKey(file: File, plaintext: ByteArray) {
        ensureWrapKey()
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val key = ks.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        file.outputStream().use { out ->
            out.write(iv.size.to4Bytes())
            out.write(iv)
            out.write(ciphertext)
        }
    }

    private fun readEncryptedKey(file: File): ByteArray? {
        return try {
            ensureWrapKey()
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
            val key = ks.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey
            val bytes = file.readBytes()
            val ivLen = bytes.sliceArray(0..3).from4Bytes()
            val iv = bytes.sliceArray(4 until 4 + ivLen)
            val ciphertext = bytes.sliceArray(4 + ivLen until bytes.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            AppLogger.e("[Proof] Failed to decrypt PGP key file", e)
            null
        }
    }

    private fun Int.to4Bytes(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte()
    )

    private fun ByteArray.from4Bytes(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or
        ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or
        (this[3].toInt() and 0xFF)

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        return ByteArray(len / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
