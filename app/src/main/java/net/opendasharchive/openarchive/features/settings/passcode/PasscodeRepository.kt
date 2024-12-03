package net.opendasharchive.openarchive.features.settings.passcode

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.opendasharchive.openarchive.util.Prefs
import org.bouncycastle.crypto.generators.SCrypt
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

interface HashingStrategy {
    suspend fun hash(passcode: String, salt: ByteArray): ByteArray
    suspend fun generateSalt(): ByteArray
    val saltLength: Int
}

// PBKDF2HashingStrategy.kt
class PBKDF2HashingStrategy : HashingStrategy {

    companion object {
        private const val ITERATIONS = 65536
        private const val KEY_LENGTH = 256
        private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_LENGTH = 16
    }

    override val saltLength: Int
        get() = SALT_LENGTH

    override suspend fun generateSalt(): ByteArray {
        val random = SecureRandom()
        return ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
    }

    override suspend fun hash(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance(HASH_ALGORITHM)
        return skf.generateSecret(spec).encoded
    }
}

// ScryptHashingStrategy.kt
class ScryptHashingStrategy : HashingStrategy {

    companion object {
        private const val N = 16384  // CPU/Memory cost parameter
        private const val r = 8      // Block size
        private const val p = 1      // Parallelization parameter
        private const val KEY_LENGTH = 32 // 256 bits
        private const val SALT_LENGTH = 16
    }

    override val saltLength: Int
        get() = SALT_LENGTH

    override suspend fun generateSalt(): ByteArray {
        val random = SecureRandom()
        return ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
    }

    override suspend fun hash(passcode: String, salt: ByteArray): ByteArray {
        return SCrypt.generate(passcode.toByteArray(), salt, N, r, p, KEY_LENGTH)
    }
}

class PasscodeRepository(
    context: Context,
    private val config: AppConfig,
    private val hashingStrategy: HashingStrategy
) {

    companion object {
        private const val SECURE_PREF_NAME = "secret_shared_prefs"
        private const val KEY_PASSCODE_HASH = "passcode_hash"
        private const val KEY_PASSCODE_SALT = "passcode_salt"
    }

    private val encryptedPrefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun generateSalt(): ByteArray {
        return hashingStrategy.generateSalt()
    }

    suspend fun hashPasscode(passcode: String, salt: ByteArray): ByteArray {
        return hashingStrategy.hash(passcode, salt)
    }

    fun storePasscodeHashAndSalt(hash: ByteArray, salt: ByteArray) {
        with(encryptedPrefs.edit()) {
            putString(KEY_PASSCODE_HASH, Base64.encodeToString(hash, Base64.DEFAULT))
            putString(KEY_PASSCODE_SALT, Base64.encodeToString(salt, Base64.DEFAULT))
            apply()
        }
        setPasscodeEnabled(true)
    }

    fun getPasscodeHashAndSalt(): Pair<ByteArray?, ByteArray?> {
        val hashBase64 = encryptedPrefs.getString(KEY_PASSCODE_HASH, null)
        val saltBase64 = encryptedPrefs.getString(KEY_PASSCODE_SALT, null)
        val passcodeHash = hashBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        val passcodeSalt = saltBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return Pair(passcodeHash, passcodeSalt)
    }

    fun setPasscodeEnabled(enabled: Boolean) {
        Prefs.passcodeEnabled = enabled
    }

    fun isPasscodeEnabled(): Boolean {
        return Prefs.passcodeEnabled
    }

    fun clearPasscode() {
        with(encryptedPrefs.edit()) {
            remove(KEY_PASSCODE_HASH)
            remove(KEY_PASSCODE_SALT)
            apply()
        }
        setPasscodeEnabled(false)
    }

    fun recordFailedAttempt() {
        val failedAttempts = Prefs.getInt(PasscodeManager.KEY_FAILED_ATTEMPTS, 0) + 1
        Prefs.putInt(PasscodeManager.KEY_FAILED_ATTEMPTS, failedAttempts)
        if (config.maxRetryLimitEnabled && failedAttempts >= config.maxFailedAttempts) {
            Prefs.putLong(PasscodeManager.KEY_LOCKOUT_TIME, System.currentTimeMillis())
        }
    }

    fun isLockedOut(): Boolean {
        if (!config.maxRetryLimitEnabled) return false
        val lockoutTime = Prefs.getLong(PasscodeManager.KEY_LOCKOUT_TIME, 0L)
        if (lockoutTime == 0L) {
            return false
        }
        val elapsedTime = System.currentTimeMillis() - lockoutTime
        return if (elapsedTime >= PasscodeManager.LOCKOUT_DURATION_MS) {
            // Lockout duration passed, reset failed attempts and lockout time
            resetFailedAttempts()
            false
        } else {
            true
        }
    }

    fun isMaxRetryLimitEnabled(): Boolean {
        return config.maxRetryLimitEnabled
    }

    fun getRemainingAttempts(): Int {
        val failedAttempts = Prefs.getInt(PasscodeManager.KEY_FAILED_ATTEMPTS, 0)
        return config.maxFailedAttempts - failedAttempts
    }

    fun resetFailedAttempts() {
        Prefs.putInt(PasscodeManager.KEY_FAILED_ATTEMPTS, 0)
        Prefs.putLong(PasscodeManager.KEY_LOCKOUT_TIME, 0L)
    }
}