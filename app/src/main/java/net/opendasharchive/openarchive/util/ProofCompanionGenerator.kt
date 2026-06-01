package net.opendasharchive.openarchive.util

import android.content.Context
import android.os.Build
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
import java.security.SecureRandom
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ProofCompanionGenerator {

    private const val SECRET_KEY_FILE = "proof_pgp_secret.bpg"
    private const val PUBLIC_KEY_FILE = "pubkey.asc"
    private const val PGP_IDENTITY = "OpenArchive Save <proof@openarchive.app>"
    private const val OTS_CALENDAR = "https://a.pool.opentimestamps.org/timestamp"
    private const val OTS_TIMEOUT_MS = 15_000

    private val PASSPHRASE = charArrayOf()

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

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
     * Generate ProofMode-compatible companion files:
     * {hash}.proof.json, {hash}.asc, {hash}.proof.json.asc, pubkey.asc, {hash}.ots
     * Returns all files ready for upload.
     */
    fun generateCompanionFiles(context: Context, evidenceFile: File, mediaHash: String): List<File> {
        if (!Prefs.useC2pa) return emptyList()
        return try {
            val outDir = File(context.filesDir, "proof_companions/$mediaHash").also { it.mkdirs() }

            // 1. Build proof JSON with ProofMode field names
            val proofJson = buildProofJson(evidenceFile, mediaHash)
            val proofJsonFile = File(outDir, "$mediaHash.proof.json")
            proofJsonFile.writeText(proofJson)

            // 2. PGP sign the media file
            val mediaSigFile = File(outDir, "$mediaHash.asc")
            pgpSign(context, evidenceFile.readBytes(), mediaSigFile)

            // 3. PGP sign the proof JSON
            val jsonSigFile = File(outDir, "$mediaHash.proof.json.asc")
            pgpSign(context, proofJson.toByteArray(Charsets.UTF_8), jsonSigFile)

            // 4. Public key (verifier needs this to check PGP sigs)
            val pubKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)

            // 5. OpenTimestamps — submit hash to OTS calendar, save .ots
            val otsFile = File(outDir, "$mediaHash.ots")
            submitOts(mediaHash, otsFile)

            buildList {
                add(proofJsonFile)
                add(mediaSigFile)
                add(jsonSigFile)
                if (pubKeyFile.exists()) add(pubKeyFile)
                if (otsFile.exists()) add(otsFile)
            }.also {
                AppLogger.i("[Proof] Generated ${it.size} companion files for $mediaHash")
            }
        } catch (e: Exception) {
            AppLogger.e("[Proof] Failed for $mediaHash", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------
    // Proof JSON — ProofMode v1 field names
    // ---------------------------------------------------------------------------

    private fun buildProofJson(file: File, hash: String): String {
        val now = isoFmt.format(Date())
        val created = isoFmt.format(Date(file.lastModified()))

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

        AppLogger.i("[Proof] Generating PGP key pair")

        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val digestCalc = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
            .get(HashAlgorithmTags.SHA1)

        val pgpKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPair, Date())

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

        // Write secret key ring
        secretFile.outputStream().use { keyRingGen.generateSecretKeyRing().encode(it) }

        // Write public key in ASCII armor
        publicFile.outputStream().use { out ->
            ArmoredOutputStream(out).use { armoredOut ->
                keyRingGen.generatePublicKeyRing().encode(armoredOut)
            }
        }
        AppLogger.i("[Proof] PGP key pair written")
    }

    private fun pgpSign(context: Context, data: ByteArray, outFile: File) {
        val secretFile = File(context.filesDir, SECRET_KEY_FILE)
        if (!secretFile.exists()) {
            ensureKeyExists(context)
            if (!secretFile.exists()) return
        }

        val secretKeyRing = PGPSecretKeyRing(
            PGPUtil.getDecoderStream(secretFile.inputStream()),
            JcaKeyFingerprintCalculator()
        )

        val secretKey = secretKeyRing.secretKey
        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(PASSPHRASE)
        )

        val sigGen = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        )
        sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        sigGen.update(data)

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
    // OpenTimestamps — submit SHA256 hash to OTS calendar
    // ---------------------------------------------------------------------------

    private fun submitOts(hexHash: String, outFile: File) {
        try {
            val hashBytes = hexToBytes(hexHash)
            val url = URL(OTS_CALENDAR)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = OTS_TIMEOUT_MS
                readTimeout = OTS_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/octet-stream")
            }
            conn.outputStream.use { it.write(hashBytes) }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                outFile.writeBytes(bytes)
                AppLogger.i("[OTS] Timestamp received (${bytes.size} bytes)")
            } else {
                AppLogger.w("[OTS] Calendar returned HTTP ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            AppLogger.w("[OTS] Submission failed: ${e.message}")
        }
    }

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
