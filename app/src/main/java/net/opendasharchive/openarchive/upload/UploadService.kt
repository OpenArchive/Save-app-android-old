package net.opendasharchive.openarchive.upload

import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.CleanInsightsManager
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.features.main.MainActivity
import net.opendasharchive.openarchive.services.Conduit
import net.opendasharchive.openarchive.util.Prefs
import timber.log.Timber
import java.io.IOException
import java.util.*

class UploadService : JobService() {

    companion object {
        private const val MY_BACKGROUND_JOB = 0
        private const val NOTIFICATION_CHANNEL_ID = "oasave_channel_1"

        fun startUploadService(activity: Activity) {
            val jobScheduler =
                ContextCompat.getSystemService(activity, JobScheduler::class.java) ?: return
            var jobBuilder = JobInfo.Builder(
                MY_BACKGROUND_JOB,
                ComponentName(activity, UploadService::class.java)
            ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                jobBuilder = jobBuilder.setUserInitiated(true)
            }
            jobScheduler.schedule(jobBuilder.build())
        }

        fun stopUploadService(context: Context) {
            val jobScheduler =
                ContextCompat.getSystemService(context, JobScheduler::class.java) ?: return
            jobScheduler.cancel(MY_BACKGROUND_JOB)
        }
    }

    private var mRunning = false
    private var mKeepUploading = true
    private val mConduits = ArrayList<Conduit>()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotificationChannel()
        Configuration.Builder().setJobSchedulerJobIdRange(0, Integer.MAX_VALUE).build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            upload {
                jobFinished(params, false)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(
                params,
                7918,
                prepNotification(),
                JOB_END_NOTIFICATION_POLICY_REMOVE
            )
        }

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        mKeepUploading = false
        for (conduit in mConduits) conduit.cancel()
        mConduits.clear()
        scope.cancel()
        return true
    }

    private suspend fun upload(completed: () -> Unit) {
        if (mRunning) {
            return completed()
        }

        mRunning = true

        if (!shouldUpload()) {
            mRunning = false

            return completed()
        }

        // Get all media items that are set into queued state.
        val results = Media.getByStatus(
            listOf(Media.Status.Queued, Media.Status.Uploading),
            Media.ORDER_PRIORITY
        ).toMutableList()

        while (mKeepUploading &&
           results.isNotEmpty()
        ) {
            val datePublish = Date()

            val media = results.removeFirst()

            if (media.sStatus != Media.Status.Uploading) {
                media.uploadDate = datePublish
                media.progress = 0 // Should we reset this?
                media.sStatus = Media.Status.Uploading
                media.statusMessage = ""
            }

            media.licenseUrl = media.project?.licenseUrl

            val collection = media.collection

            if (collection?.uploadDate == null) {
                collection?.uploadDate = datePublish
                collection?.save()
            }

            try {
                upload(media)
            } catch (ioe: IOException) {
                Timber.d(ioe)

                media.statusMessage = "error in uploading media: " + ioe.message
                media.sStatus = Media.Status.Error
                media.save()

                BroadcastManager.postChange(applicationContext, media.collectionId, media.id)
            }

            if (!mKeepUploading) break // Time to end this.
        }

        mRunning = false
        completed()
    }

    @Throws(IOException::class)
    private suspend fun upload(media: Media): Boolean {

        val conduit = Conduit.get(media, this)
            ?: return false

        media.sStatus = Media.Status.Uploading
        media.save()
        BroadcastManager.postChange(this, media.collectionId, media.id)

        CleanInsightsManager.measureEvent("upload", "try_upload", media.space?.tType?.friendlyName)

        mConduits.add(conduit)
        scope.launch {
            conduit.upload()
            mConduits.remove(conduit)
        }

        return true
    }

    /**
     * Check if online, and connected to the appropriate network type.
     */
    private fun shouldUpload(): Boolean {
        val requireUnmetered = Prefs.uploadWifiOnly

        if (isNetworkAvailable(requireUnmetered)) return true

        val type =
            if (requireUnmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY

        // Try again when there is a network.
        val job = JobInfo.Builder(
            MY_BACKGROUND_JOB,
            ComponentName(this, UploadService::class.java)
        )
            .setRequiredNetworkType(type)
            .setRequiresCharging(false)
            .build()

        (getSystemService(JOB_SCHEDULER_SERVICE) as? JobScheduler)?.schedule(job)

        return false
    }

    private fun isNetworkAvailable(requireUnmetered: Boolean): Boolean {
        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false

            when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    return true
                }

                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    return !requireUnmetered
                }

                cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    return true
                }
            }

            return false
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo

            @Suppress("DEPRECATION")
            return info?.isConnected == true && (!requireUnmetered
                    || info.type == ConnectivityManager.TYPE_WIFI
                    || info.type == ConnectivityManager.TYPE_ETHERNET)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, getString(R.string.uploads),
            NotificationManager.IMPORTANCE_LOW
        )

        channel.description = getString(R.string.uploads_notification_descriptions)
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET

        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.createNotificationChannel(channel)
    }

    private fun prepNotification(): Notification {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java), flag
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_notify)
            .setContentTitle(getString(R.string.uploading))
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setContentIntent(pendingIntent)
            .build()
    }
}