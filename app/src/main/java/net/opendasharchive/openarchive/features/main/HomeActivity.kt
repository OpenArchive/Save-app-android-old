package net.opendasharchive.openarchive.features.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.core.presentation.theme.SaveAppTheme
import net.opendasharchive.openarchive.features.core.BaseComposeActivity
import net.opendasharchive.openarchive.features.main.ui.Navigator
import net.opendasharchive.openarchive.features.main.ui.SaveNavGraph
import net.opendasharchive.openarchive.core.config.AppConfig
import net.opendasharchive.openarchive.features.settings.passcode.PasscodeGate
import net.opendasharchive.openarchive.services.snowbird.service.SnowbirdService
import net.opendasharchive.openarchive.util.PermissionManager
import org.koin.android.ext.android.inject
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.activityRetainedScope
import net.opendasharchive.openarchive.features.main.ui.SharedImportState
import net.opendasharchive.openarchive.upload.UploadGate
import net.opendasharchive.openarchive.upload.UploadJobScheduler
import net.opendasharchive.openarchive.util.C2paHelper

class HomeActivity : BaseComposeActivity(), AndroidScopeComponent {

    override val scope by activityRetainedScope()

    private val appConfig by inject<AppConfig>()
    private val navigator by inject<Navigator>()
    private val uploadJobScheduler by inject<UploadJobScheduler>()
    private val uploadGate by inject<UploadGate>()
    private val passcodeGate by inject<PasscodeGate>()
    private val sharedImportState by inject<SharedImportState>()
    private lateinit var permissionManager: PermissionManager

    /** URIs received via share sheet while the app was locked — delivered after authentication. */
    private var pendingSharedUris: List<Uri>? = null

    /**
     * True only on the very first onStart after a fresh launch (not config-change recreation).
     * Dark mode toggle recreates the activity — savedInstanceState is non-null in that case,
     * so one-shot setup that must not repeat is gated on savedInstanceState == null in onCreate
     * and this flag in onStart.
     */
    private var isFirstStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isRecreating = savedInstanceState != null

        // Always call — needed to transition away from Theme.SaveApp.Starting on every recreation
        installSplashScreen()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = getColor(R.color.colorTertiary),
                darkScrim = getColor(R.color.colorTertiary)
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = getColor(R.color.colorTertiary),
                darkScrim = getColor(R.color.colorTertiary)
            )
        )

        setContent {
            SaveAppTheme {
                SaveNavGraph(
                    dialogManager,
                    navigator
                )
            }
        }

        if (appConfig.isDwebEnabled && !isRecreating) {
            permissionManager = PermissionManager(this, dialogManager)
            permissionManager.checkNotificationPermission {
                AppLogger.i("Notification permission granted")
            }
            handleIntent(intent)
            startForegroundService(Intent(this, SnowbirdService::class.java))
        }

        if (!isRecreating) {
            importSharedMedia(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        C2paHelper.init(this)

        // On every foreground return: if already unlocked, re-schedule any queued uploads.
        // This recovers from stalled JobService runs (e.g. OS killed the job mid-upload).
        if (!passcodeGate.locked.value) {
            uploadGate.checkIfQueued { uploadJobScheduler.schedule() }
        }

        if (isFirstStart) {
            isFirstStart = false
            lifecycleScope.launch {
                // Wait until app is unlocked before flushing pending share URIs and
                // running the initial upload gate check — prevents dialogs on passcode screen.
                passcodeGate.locked
                    .filter { !it }
                    .take(1)
                    .collect {
                        uploadGate.checkIfQueued { uploadJobScheduler.schedule() }
                        pendingSharedUris?.let { uris ->
                            sharedImportState.setPendingUris(uris)
                            pendingSharedUris = null
                        }
                    }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedMedia(intent)
    }

    // ----- Permissions & Intent Handling -----
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.takeIf { it.scheme == "save-veilid" }?.let { processUri(it) }
        }
    }

    private fun processUri(uri: Uri) {
        val path = uri.path
        val queryParams = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) }
        AppLogger.d("Path: $path, QueryParams: $queryParams")
    }

    private fun importSharedMedia(intent: Intent?) {
        if (intent == null) { AppLogger.d("SHARE_DEBUG: intent null, skipping"); return }
        val action = intent.action
        val type = intent.type
        AppLogger.d("SHARE_DEBUG: importSharedMedia action=$action type=$type")

        // Defense-in-depth: reject MIME types not explicitly supported (mirrors manifest intent-filters)
        val allowedMimeTypes = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "image/heic", "image/heif", "image/tiff", "image/bmp",
            "video/mp4", "video/quicktime", "video/x-msvideo",
            "video/x-matroska", "video/webm", "video/3gpp",
            "audio/mpeg", "audio/aac", "audio/flac", "audio/ogg",
            "audio/wav", "audio/x-wav", "audio/mp4", "audio/opus",
            "application/pdf"
        )
        if (type != null && type !in allowedMimeTypes) {
            AppLogger.d("SHARE_DEBUG: MIME type $type not allowed, returning")
            return
        }

        if ((Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action) && type != null) {
            val uris = mutableListOf<Uri>()

            if (Intent.ACTION_SEND == action) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }

            AppLogger.d("SHARE_DEBUG: uris.size=${uris.size} locked=${passcodeGate.locked.value}")

            if (uris.isNotEmpty()) {
                if (passcodeGate.locked.value) {
                    AppLogger.d("SHARE_DEBUG: app locked, storing pending uris")
                    pendingSharedUris = uris
                } else {
                    AppLogger.d("SHARE_DEBUG: calling sharedImportState.setPendingUris")
                    sharedImportState.setPendingUris(uris)
                }
            }
        } else {
            AppLogger.d("SHARE_DEBUG: action/type mismatch, not ACTION_SEND. action=$action type=$type")
        }
    }
}
