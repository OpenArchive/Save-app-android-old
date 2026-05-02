package net.opendasharchive.openarchive.upload

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.domain.VaultType
import net.opendasharchive.openarchive.core.repositories.MediaRepository
import net.opendasharchive.openarchive.features.core.UiText
import net.opendasharchive.openarchive.features.core.asUiText
import net.opendasharchive.openarchive.features.core.dialog.DialogStateManager
import net.opendasharchive.openarchive.features.core.dialog.DialogType
import net.opendasharchive.openarchive.features.core.dialog.showDialog
import net.opendasharchive.openarchive.services.tor.TorServiceManager
import net.opendasharchive.openarchive.services.tor.TorStatus
import net.opendasharchive.openarchive.util.NetworkUtils
import net.opendasharchive.openarchive.util.Prefs

/**
 * Central gate for all user-initiated and background-resume uploads.
 *
 * Checks (in order):
 *  1. Wi-Fi only setting — applies to ALL vault types.
 *     "Allow any connection" → disables wifi-only and proceeds immediately.
 *     "Wait for Wi-Fi" → schedules job with NETWORK_TYPE_UNMETERED; uploads
 *     resume automatically when Wi-Fi connects.
 *  2. Tor connection — applies to all vault types EXCEPT DWeb (Snowbird routes its own traffic).
 *     "Turn off Tor and proceed" → disables Tor and proceeds immediately.
 *     "Wait for Tor" → watches torStatus flow; uploads resume automatically
 *     when Tor becomes connected.
 *
 * [check] — for explicit user actions (always runs checks).
 * [checkIfQueued] — for background resume (skips checks silently if queue is empty).
 *
 * Pass [vaultType] so DWeb uploads skip the Tor check.
 * Pass [onProceed] as the action to run once all checks pass.
 */
class UploadGate(
    private val application: Application,
    private val dialogManager: DialogStateManager,
    private val torServiceManager: TorServiceManager,
    private val mediaRepository: MediaRepository,
    private val uploadJobScheduler: UploadJobScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var torWatcherJob: Job? = null

    init {
        // When TOR reconnects in background, reschedule any queued uploads
        scope.launch {
            torServiceManager.torStatus
                .filter { it == TorStatus.On || it is TorStatus.Verified }
                .collect {
                    if (Prefs.useTor && mediaRepository.getQueue().isNotEmpty()) {
                        uploadJobScheduler.schedule()
                    }
                }
        }
    }

    /** For explicit user-initiated upload actions. Always runs checks. */
    fun check(vaultType: VaultType? = null, onProceed: () -> Unit) {
        checkWifi(vaultType = vaultType, onProceed = onProceed)
    }

    /**
     * For background resume (e.g. onResume).
     * Skips checks silently if nothing is queued — avoids spurious dialogs
     * every time the user returns to the app with an empty queue.
     */
    fun checkIfQueued(vaultType: VaultType? = null, onProceed: () -> Unit) {
        scope.launch {
            val hasQueued = mediaRepository.getQueue().isNotEmpty()
            if (hasQueued) {
                checkWifi(vaultType = vaultType, onProceed = onProceed)
            }
        }
    }

    private fun checkWifi(vaultType: VaultType?, onProceed: () -> Unit) {
        if (Prefs.uploadWifiOnly && !NetworkUtils.isOnWifi(application)) {
            // Schedule a job that fires when Wi-Fi is available. This covers the
            // "Wait for Wi-Fi" path — JobScheduler auto-resumes uploads on reconnect.
            scheduleWhenWifiAvailable()

            dialogManager.showDialog(dialogManager.requireResourceProvider()) {
                type = DialogType.Warning
                title = R.string.wifi_not_connected.asUiText()
                message = R.string.wifi_required_upload_message.asUiText()
                positiveButton {
                    // Permanently disable WiFi-only setting and proceed immediately
                    text = UiText.Resource(R.string.allow_any_connection)
                    action = {
                        Prefs.uploadWifiOnly = false
                        checkTor(vaultType = vaultType, onProceed = onProceed)
                    }
                }
                destructiveButton {
                    // Dismiss — unmetered job above handles auto-resume when Wi-Fi connects
                    text = UiText.Resource(R.string.wait_for_wifi)
                }
            }
            return
        }
        checkTor(vaultType = vaultType, onProceed = onProceed)
    }

    private fun checkTor(vaultType: VaultType?, onProceed: () -> Unit) {
        // DWeb (Snowbird) manages its own networking — Tor routing is irrelevant.
        if (vaultType == VaultType.DWEB_STORAGE) {
            onProceed()
            return
        }

        if (Prefs.useTor && !torServiceManager.isReady()) {
            val torStatus = torServiceManager.torStatus.value
            val messageRes = when (torStatus) {
                is TorStatus.Starting -> R.string.tor_still_connecting_message
                is TorStatus.Error    -> R.string.tor_error_message
                else                  -> R.string.tor_not_connected_message
            }

            // If Tor is still connecting (not errored), watch for it to become ready
            // and auto-proceed when it does — covers the "Wait for Tor" path.
            if (torStatus is TorStatus.Starting) {
                watchTorAndProceed(onProceed)
            }

            dialogManager.showDialog(dialogManager.requireResourceProvider()) {
                type = DialogType.Warning
                title = R.string.tor_not_connected.asUiText()
                message = messageRes.asUiText()
                positiveButton {
                    // Permanently disable Tor and proceed immediately
                    text = UiText.Resource(R.string.disable_tor)
                    action = {
                        torWatcherJob?.cancel()
                        Prefs.useTor = false
                        torServiceManager.stop()
                        onProceed()
                    }
                }
                destructiveButton {
                    // Dismiss — torWatcherJob above handles auto-resume when Tor connects
                    text = UiText.Resource(R.string.wait_for_tor)
                }
            }
            return
        }

        onProceed()
    }

    /**
     * Schedules the upload job to fire when an unmetered (Wi-Fi) network is available.
     * Replaces any existing pending job with the same ID.
     */
    private fun scheduleWhenWifiAvailable() {
        val jobScheduler = application.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            UploadJobConfig.JOB_ID,
            ComponentName(application, UploadService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresCharging(false)
            .build()
        jobScheduler.schedule(job)
    }

    /**
     * Observes torStatus and calls [onProceed] as soon as Tor becomes ready.
     * Cancels any previous watcher to avoid double-triggering.
     */
    private fun watchTorAndProceed(onProceed: () -> Unit) {
        torWatcherJob?.cancel()
        torWatcherJob = scope.launch {
            torServiceManager.torStatus
                .filter { it == TorStatus.On || it is TorStatus.Verified }
                .first()
            // Tor is now ready — proceed if Tor is still enabled (user may have disabled it)
            if (Prefs.useTor) {
                onProceed()
            }
        }
    }
}
