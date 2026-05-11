package net.opendasharchive.openarchive

import android.app.Application
import android.app.NotificationChannel
import android.os.Build
import android.app.NotificationManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.room3.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.orm.SugarApp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import net.opendasharchive.openarchive.core.repositories.CacheCleanupWorker
import net.opendasharchive.openarchive.core.di.torModule
import net.opendasharchive.openarchive.core.security.TinkVaultCredentialStore
import net.opendasharchive.openarchive.services.tor.TorConstants
import net.opendasharchive.openarchive.services.tor.TorServiceManager
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.analytics.api.AnalyticsManager
import net.opendasharchive.openarchive.analytics.api.session.SessionTracker
import net.opendasharchive.openarchive.core.security.C2paKeyStore
import net.opendasharchive.openarchive.analytics.di.analyticsModule
import net.opendasharchive.openarchive.db.AppDatabase
import net.opendasharchive.openarchive.db.MigrationWorker
import net.opendasharchive.openarchive.db.SugarToRoomMigrator
import net.opendasharchive.openarchive.core.di.coreModule
import net.opendasharchive.openarchive.core.di.databaseModule
import net.opendasharchive.openarchive.core.di.featuresModule
import net.opendasharchive.openarchive.core.di.passcodeModule
import net.opendasharchive.openarchive.features.settings.passcode.PasscodeGate
import net.opendasharchive.openarchive.core.di.retrofitModule
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.util.C2paHelper
import net.opendasharchive.openarchive.core.repositories.MediaRepository
import net.opendasharchive.openarchive.util.CleanInsightsManager
import net.opendasharchive.openarchive.util.Prefs
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class SaveApp : SugarApp(), SingletonImageLoader.Factory, DefaultLifecycleObserver {

    // Inject analytics dependencies
    private val analyticsManager: AnalyticsManager by inject()
    private val sessionTracker: SessionTracker by inject()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Initialize ACRA for FOSS builds (no-op for GMS builds)
        AppLogger.initAcra(this)
    }

    private fun applyTheme() {

        val useDarkMode = Prefs.getBoolean(getString(R.string.pref_key_use_dark_mode), false)
        val nightMode = if (useDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onCreate() {
        // L2: Delete Sugar DB BEFORE SugarApp.onCreate() opens it. Reading raw prefs here
        // (before Prefs.load) is safe — attachBaseContext has already run.
        // Sugar DB cannot be deleted in the same session it was opened (SQLITE_READONLY_DBMOVED 1032),
        // so deletion is always deferred to the next launch via `sugar_db_delete_pending`.
        val rawPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val deletePending = rawPrefs.getBoolean("sugar_db_delete_pending", false)
        val sugarDbExistedBeforeDelete = if (deletePending) {
            val existed = getDatabasePath("openarchive.db").exists()
            deleteDatabase("openarchive.db")
            // Clear the flag synchronously so it doesn't trigger again on subsequent launches
            rawPrefs.edit().putBoolean("sugar_db_delete_pending", false).commit()
            existed
        } else null

        super<SugarApp>.onCreate()

        // Initialize logging first (applicationContext valid after super.onCreate)
        AppLogger.init(applicationContext, initDebugger = true)

        if (sugarDbExistedBeforeDelete != null) {
            AppLogger.i("DB: Sugar DB ${if (sugarDbExistedBeforeDelete) "deleted" else "already absent"} — Room only from here")
        }

        // ACRA spawns a secondary :acra process to collect/send crash reports.
        // Skip all main-process initialisation (WorkManager, Koin, TOR, analytics) there.
        if (!isMainProcess()) return

        Prefs.load(this)

        // Initialize C2PA Helper
        C2paHelper.init(this)

        // --- 2-launch synchronous migration strategy ---
        // L1: If Sugar DB exists and Room migration hasn't run yet, open Room directly
        //     (before Koin), run migration synchronously, then mark done.
        //     Sugar DB deletion is deferred to L2 (SQLITE_READONLY_DBMOVED constraint).
        // L2: Deletion of Sugar DB already happened at the top of onCreate (before super).
        // New install: no Sugar DB → set isRoomMigrated immediately.
        if (!Prefs.isRoomMigrated) {
            val sugarDbExists = getDatabasePath("openarchive.db").exists()
            if (!sugarDbExists) {
                Prefs.isRoomMigrated = true
                AppLogger.i("DB: New install — no Sugar DB, using Room from start")
            } else {
                AppLogger.i("DB: L1 — Sugar DB found, running synchronous migration before Koin")
                val tempDb = Room.databaseBuilder(
                    applicationContext,
                    AppDatabase::class.java,
                    "openarchive.db_room"
                ).build()
                try {
                    val credentialStore = TinkVaultCredentialStore(applicationContext)
                    runBlocking {
                        SugarToRoomMigrator.migrate(
                            vaultDao = tempDb.vaultDao(),
                            archiveDao = tempDb.archiveDao(),
                            submissionDao = tempDb.submissionDao(),
                            evidenceDao = tempDb.evidenceDao(),
                            migrationDao = tempDb.migrationDao(),
                            credentialStore = credentialStore
                        )
                    }
                    Prefs.isRoomMigrated = true
                    Prefs.isSugarDbDeletePending = true
                    AppLogger.i("DB: L1 migration complete — Sugar DB deletion scheduled for next launch")
                } catch (e: Exception) {
                    AppLogger.e("DB: L1 synchronous migration failed — will retry via MigrationWorker", e)
                    // Fallback: enqueue WorkManager-based migration so user data is not lost
                    WorkManager.getInstance(this).enqueueUniqueWork(
                        "RoomMigration", ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<MigrationWorker>().build()
                    )
                } finally {
                    tempDb.close()
                }
            }
        }

        // Initialize Koin DI
        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@SaveApp)
            modules(
                databaseModule,
                coreModule,
                passcodeModule,
                featuresModule,
                retrofitModule,
                torModule,
                analyticsModule(
                    mixpanelToken = getString(R.string.mixpanel_key),
                    cleanInsightsConsentChecker = { CleanInsightsManager.hasConsent() }
                )
            )
        }

        // Reset any items stuck in UPLOADING from a previous session that was force-killed
        // or crashed (no onStopJob callback). Without this, the UI shows a permanent upload
        // spinner and the items never re-enter the queue.
        val mediaRepository: MediaRepository by inject()
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            mediaRepository.resetStaleUploading()
        }

        // Register PasscodeGate once on the process lifecycle — not per-activity — so that
        // activity recreation (e.g. dark mode toggle) does not cause spurious onStop → onStart
        // transitions that reset auth state and prompt for passcode.
        val passcodeGate: PasscodeGate by inject()
        ProcessLifecycleOwner.get().lifecycle.addObserver(passcodeGate)

        applyTheme()

        // Migrate C2PA keys from plaintext SharedPreferences to SecureStorage (one-time)
        val c2paKeyStore: C2paKeyStore by inject()
        c2paKeyStore.migrateFromPrefsIfNeeded(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        )

        // Schedule periodic cache cleanup (runs every 7 days when battery is not low)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CacheCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                CacheCleanupWorker.REPEAT_INTERVAL_DAYS,
                java.util.concurrent.TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        )

        // Start embedded Tor service if enabled.
        // On Android 12+ we cannot call startForegroundService() when the process is launched
        // from the background (e.g. Android restarting SnowbirdService via START_STICKY).
        // Strategy: try to start immediately (gives Tor a head-start before Snowbird initialises),
        // and if the OS rejects it we register a one-shot observer to retry on first foreground entry.
        if (Prefs.useTor) {
            val torServiceManager: TorServiceManager by inject()
            try {
                torServiceManager.start()
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            torServiceManager.start()
                            owner.lifecycle.removeObserver(this)
                        }
                    })
                } else {
                    throw e
                }
            }
        }

        // Legacy CleanInsightsManager (kept for backwards compatibility)
        CleanInsightsManager.init(this)

        // Initialize analytics asynchronously BEFORE registering lifecycle observer
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            analyticsManager.initialize(this@SaveApp)

            // Set analytics manager for AppLogger
            AppLogger.setAnalyticsManager(analyticsManager)

            // Set app version for session tracker
            (sessionTracker as? net.opendasharchive.openarchive.analytics.api.session.SessionTrackerImpl)?.setAppVersion(
                BuildConfig.VERSION_NAME
            )

            // Set user properties (GDPR-compliant)
            analyticsManager.setUserProperty("app_version", BuildConfig.VERSION_NAME)
            analyticsManager.setUserProperty("device_type", "android")

            // Register app lifecycle observer AFTER analytics is initialized
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@SaveApp)
        }

        createSnowbirdNotificationChannel()
        createTorNotificationChannel()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // App came to foreground
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            sessionTracker.startSession()
            sessionTracker.onForeground()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // App went to background
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            sessionTracker.onBackground()
            sessionTracker.endSession()

            // Persist analytics data
            analyticsManager.flush()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up Tor service when app is terminated
        if (Prefs.useTor) {
            val torServiceManager: TorServiceManager by inject()
            torServiceManager.cleanup()
        }
    }

    private fun createTorNotificationChannel() {
        val channel = NotificationChannel(
            TorConstants.TOR_NOTIFICATION_CHANNEL_ID,
            getString(R.string.tor_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tor_notification_channel_description)
        }

        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createSnowbirdNotificationChannel() {
        val silentChannel = NotificationChannel(
            SNOWBIRD_SERVICE_CHANNEL_SILENT,
            "Dweb Storage",
            NotificationManager.IMPORTANCE_LOW
        )

        val chimeChannel = NotificationChannel(
            SNOWBIRD_SERVICE_CHANNEL_CHIME,
            "Dweb Storage",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(chimeChannel)
        notificationManager.createNotificationChannel(silentChannel)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            (getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return name == packageName
    }

    companion object {
        const val SNOWBIRD_SERVICE_ID = 2601
        const val SNOWBIRD_SERVICE_CHANNEL_CHIME = "snowbird_service_channel_chime"
        const val SNOWBIRD_SERVICE_CHANNEL_SILENT = "snowbird_service_channel_silent"

        const val TOR_SERVICE_ID = 2602
        const val TOR_SERVICE_CHANNEL = "tor_service_channel"
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .logger(AppLogger.imageLogger)
            .build()
    }
}
