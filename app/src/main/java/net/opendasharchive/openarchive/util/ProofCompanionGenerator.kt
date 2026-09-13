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
    private const val OTS_CALENDAR = "https://a.pool.opentimestamps.org/digest"
    private const val OTS_TIMEOUT_MS = 15_000

    // OpenTimestamps detached-file format: fixed 31-byte magic + 1-byte major version.
    // Every valid .ots file (and every OTS verifier, incl. opentimestamps.org) requires this
    // preamble before the hash-op tag + digest + serialized timestamp.
    private val OTS_HEADER_MAGIC = byteArrayOf(
        0x00, 0x4f, 0x70, 0x65, 0x6e, 0x54, 0x69, 0x6d, 0x65, 0x73, 0x74, 0x61, 0x6d, 0x70, 0x73,
        0x00, 0x00, 0x50, 0x72, 0x6f, 0x6f, 0x66, 0x00,
        0xbf.toByte(), 0x89.toByte(), 0xe2.toByte(), 0xe8.toByte(), 0x84.toByte(), 0xe8.toByte(), 0x92.toByte(), 0x94.toByte()
    )
    private const val OTS_MAJOR_VERSION: Byte = 0x01
    private const val OTS_OP_SHA256: Byte = 0x08

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
     * Called at capture time. Generates proof.json, proof.csv, PGP signatures, and
     * HowToVerifyProofData.txt immediately after C2PA embedding while the file bytes are
     * known-final. Passing [metadata] ensures all sensor/device fields make it into the proof
     * without round-tripping through EXIF. Also submits the OTS timestamp and requests device
     * attestation (.gst) right away — all proof material is complete at capture time; nothing
     * is generated later at upload time.
     */
    suspend fun generateLocalProof(
        context: Context,
        evidenceFile: File,
        mediaHash: String,
        metadata: MetadataCollector.CaptureMetadata? = null
    ) {
        if (!Prefs.useC2pa) return
        withContext(Dispatchers.IO) {
            try {
                val outDir = File(context.filesDir, "proof_companions/$mediaHash").also { it.mkdirs() }

                val fields = buildProofFields(evidenceFile, mediaHash, metadata)

                val proofJson = buildProofJson(fields)
                val proofCsv  = buildProofCsv(fields)

                val proofJsonFile = File(outDir, "$mediaHash.proof.json")
                val proofCsvFile  = File(outDir, "$mediaHash.proof.csv")
                proofJsonFile.writeText(proofJson)
                proofCsvFile.writeText(proofCsv)

                pgpSignStream(context, evidenceFile, File(outDir, "$mediaHash.asc"))
                pgpSign(context, proofJson.toByteArray(Charsets.UTF_8), File(outDir, "$mediaHash.proof.json.asc"))
                pgpSign(context, proofCsv.toByteArray(Charsets.UTF_8), File(outDir, "$mediaHash.proof.csv.asc"))

                File(outDir, "HowToVerifyProofData.txt").writeText(
                    buildHowToVerify(evidenceFile.name, mediaHash)
                )

                // OTS calendar submission and device attestation — done now so every proof
                // artifact exists immediately after capture, not deferred to upload time.
                submitOts(context, mediaHash, File(outDir, "$mediaHash.ots"))
                SafetyNetHelper.requestGst(context, mediaHash, File(outDir, "$mediaHash.gst"))

                AppLogger.i("[Proof] Local proof generated at capture time for $mediaHash")
            } catch (e: Exception) {
                AppLogger.e("[Proof] Local proof generation failed for $mediaHash", e)
            }
        }
    }

    /**
     * Called at upload time. All proof material — including OTS and .gst — is generated at
     * capture time by [generateLocalProof]; this only assembles the file list, with a
     * defensive fallback that regenerates anything missing (should not occur for camera
     * captures, but covers edge cases like a process death between capture and upload).
     */
    suspend fun prepareForUpload(context: Context, evidenceFile: File, mediaHash: String): List<File> = withContext(Dispatchers.IO) {
        if (!Prefs.useC2pa) return@withContext emptyList()
        try {
            val outDir = File(context.filesDir, "proof_companions/$mediaHash").also { it.mkdirs() }
            val proofJsonFile  = File(outDir, "$mediaHash.proof.json")
            val proofCsvFile   = File(outDir, "$mediaHash.proof.csv")
            val mediaSigFile   = File(outDir, "$mediaHash.asc")
            val jsonSigFile    = File(outDir, "$mediaHash.proof.json.asc")
            val csvSigFile     = File(outDir, "$mediaHash.proof.csv.asc")
            val howToFile      = File(outDir, "HowToVerifyProofData.txt")
            val otsFile        = File(outDir, "$mediaHash.ots")
            val gstFile        = File(outDir, "$mediaHash.gst")

            // Defensive fallback — should not occur for camera captures but handles edge cases
            // (e.g. process death between capture and upload)
            if (!proofJsonFile.exists() || !mediaSigFile.exists() || !jsonSigFile.exists()) {
                AppLogger.w("[Proof] Local proof missing for $mediaHash — generating at upload time")
                val fields = buildProofFields(evidenceFile, mediaHash, null)
                proofJsonFile.writeText(buildProofJson(fields))
                proofCsvFile.writeText(buildProofCsv(fields))
                pgpSignStream(context, evidenceFile, mediaSigFile)
                pgpSign(context, proofJsonFile.readBytes(), jsonSigFile)
                pgpSign(context, proofCsvFile.readBytes(), csvSigFile)
                howToFile.writeText(buildHowToVerify(evidenceFile.name, mediaHash))
            }
            if (!otsFile.exists()) {
                AppLogger.w("[Proof] OTS missing for $mediaHash — submitting at upload time")
                submitOts(context, mediaHash, otsFile)
            }
            if (!gstFile.exists()) {
                SafetyNetHelper.requestGst(context, mediaHash, gstFile)
            }

            val pubKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)
            buildList {
                add(proofJsonFile)
                add(proofCsvFile)
                add(mediaSigFile)
                add(jsonSigFile)
                if (csvSigFile.exists()) add(csvSigFile)
                if (pubKeyFile.exists()) add(pubKeyFile)
                if (otsFile.exists()) add(otsFile)
                if (gstFile.exists()) add(gstFile)
                if (howToFile.exists()) add(howToFile)
            }.also {
                AppLogger.i("[Proof] ${it.size} companion files ready for upload: $mediaHash")
            }
        } catch (e: Exception) {
            AppLogger.e("[Proof] Upload prep failed for $mediaHash", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------
    // Proof fields — shared between JSON and CSV builders
    // ---------------------------------------------------------------------------

    private fun buildProofFields(
        file: File,
        hash: String,
        metadata: MetadataCollector.CaptureMetadata?
    ): LinkedHashMap<String, String?> {
        val now = isoFmt.format(Instant.now())

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
        fields["File Path"]        = file.absolutePath
        fields["File Created"]     = created
        fields["File Modified"]    = created
        fields["Proof Generated"]  = now
        fields["Notes"]            = "${metadata?.appName ?: "OpenArchive Save"} ${BuildConfig.VERSION_NAME}"
        fields["App.Name"]         = metadata?.appName
        fields["Manufacturer"]     = metadata?.deviceMake ?: Build.MANUFACTURER
        fields["Device.Brand"]     = metadata?.deviceBrand ?: Build.BRAND
        fields["Device.Model"]     = metadata?.deviceModel ?: Build.MODEL
        fields["Hardware"]         = "${metadata?.deviceMake ?: Build.MANUFACTURER} ${metadata?.deviceModel ?: Build.MODEL}"
        fields["Locale"]           = metadata?.locale ?: Locale.getDefault().country
        fields["Language"]         = metadata?.language ?: Locale.getDefault().displayLanguage
        fields["Screen.Size"]      = metadata?.screenSizeInches?.let { "%.2f".format(it) }
        fields["Network.Type"]     = metadata?.networkType
        fields["Network.IPv4"]     = metadata?.ipv4
        fields["Network.IPv6"]     = metadata?.ipv6
        fields["Network.CellInfo"] = metadata?.cellInfo

        // GPS — prefer live metadata, fall back to EXIF
        if (metadata?.latitude != null && metadata.longitude != null) {
            fields["Location.Latitude"]  = metadata.latitude.toString()
            fields["Location.Longitude"] = metadata.longitude.toString()
            metadata.locationAltitude?.let  { fields["Location.Altitude"]  = it.toString() }
            metadata.locationSpeed?.let     { fields["Location.Speed"]     = it.toString() }
            metadata.locationBearing?.let   { fields["Location.Bearing"]   = it.toString() }
            metadata.locationProvider?.let  { fields["Location.Provider"]  = it }
            metadata.locationAccuracy?.let  { fields["Location.Accuracy"]  = it.toString() }
            metadata.locationTime?.let      { fields["Location.Time"]      = it.toString() }
        } else {
            try {
                val exif = ExifInterface(file.absolutePath)
                val latLon = exif.latLong
                if (latLon != null) {
                    fields["Location.Latitude"]  = latLon[0].toString()
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
        }

        return fields
    }

    // ---------------------------------------------------------------------------
    // Proof JSON — ProofMode v1 field names
    // ---------------------------------------------------------------------------

    private fun buildProofJson(fields: LinkedHashMap<String, String?>): String {
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
    // Proof CSV — same fields, header + data row
    // ---------------------------------------------------------------------------

    private fun buildProofCsv(fields: LinkedHashMap<String, String?>): String {
        val present = fields.entries.filter { it.value != null }
        val header = present.joinToString(",") { csvEscape(it.key) }
        val data   = present.joinToString(",") { csvEscape(it.value!!) }
        return "$header\n$data\n"
    }

    // ---------------------------------------------------------------------------
    // HowToVerifyProofData.txt — dynamically generated with real hash/filename
    // ---------------------------------------------------------------------------

    private fun buildHowToVerify(mediaFileName: String, hash: String): String = """
Brief information on how to verify the media file, proof and signatures contained in a ProofMode bundle.
Please visit https://proofmode.org or email support@guardianproject.info for more information.

1) Import public key shared from ProofMode:

gpg --import pubkey.asc

gpg: key xxx: public key "proof@openarchive.app" imported
gpg: Total number processed: 1
gpg:               imported: 1

2) Check the hash of the media file against the hash in the proof metadata:

sha256sum $mediaFileName

$hash  $mediaFileName

3) Verify signature of the media file:

gpg --dearmor pubkey.asc
gpg --no-default-keyring --keyring ./pubkey.asc.gpg --homedir ./ --verify $hash.asc $mediaFileName

gpg: Good signature from "proof@openarchive.app" [unknown]

4) Verify signature of the ProofMode CSV data:

gpg --verify $hash.proof.csv.asc $hash.proof.csv

gpg: Good signature from "proof@openarchive.app" [unknown]

5) Verify signature of the ProofMode JSON data:

gpg --verify $hash.proof.json.asc $hash.proof.json

gpg: Good signature from "proof@openarchive.app" [unknown]

6) If a .ots file is present, visit https://opentimestamps.org/ and upload $hash.ots to verify
   the Bitcoin blockchain notarisation. (It can take several hours for the timestamp to confirm.)

7) If a .gst file is present, that is a JWT from the Google Play Integrity API attesting device
   integrity at capture time. Decode the JWT value at https://jwt.io/ to inspect the claims.
""".trimStart()

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
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Accept", "application/octet-stream")
            }
            conn.outputStream.use { it.write(hashBytes) }
            val success = conn.responseCode == 200
            if (success) {
                val calendarResponse = conn.inputStream.use { it.readBytes() }
                outFile.parentFile?.mkdirs()
                // Assemble a spec-compliant detached .ots: magic header + version + hash-op tag
                // + digest, followed by the calendar's serialized (pending) timestamp. Writing
                // the raw calendar response alone (previous behavior) produced a file no OTS
                // verifier could read.
                outFile.outputStream().use { out ->
                    out.write(OTS_HEADER_MAGIC)
                    out.write(byteArrayOf(OTS_MAJOR_VERSION, OTS_OP_SHA256))
                    out.write(hashBytes)
                    out.write(calendarResponse)
                }
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

    private fun csvEscape(s: String): String {
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
    }
}
