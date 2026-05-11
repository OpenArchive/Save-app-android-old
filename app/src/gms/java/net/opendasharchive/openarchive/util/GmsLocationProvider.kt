package net.opendasharchive.openarchive.util

import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.opendasharchive.openarchive.core.logger.AppLogger
import kotlin.coroutines.resume

/**
 * GMS implementation of LocationProvider using FusedLocationProviderClient.
 * Used in Google Play builds.
 */
class GmsLocationProvider(private val context: Context) : LocationProvider {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun getCurrentLocation(timeoutMs: Long): Location? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val cancellationTokenSource = CancellationTokenSource()

                        val locationRequest = CurrentLocationRequest.Builder()
                            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                            .setMaxUpdateAgeMillis(60_000)
                            .build()

                        fusedLocationClient.getCurrentLocation(
                            locationRequest,
                            cancellationTokenSource.token
                        ).addOnSuccessListener { location ->
                            if (continuation.isActive) {
                                if (location != null) {
                                    AppLogger.d("[GmsLocation] Got location: ${location.latitude}, ${location.longitude}")
                                } else {
                                    AppLogger.w("[GmsLocation] Location is null")
                                }
                                continuation.resume(location)
                            }
                        }.addOnFailureListener { exception ->
                            if (continuation.isActive) {
                                AppLogger.w("[GmsLocation] Failed to get location: ${exception.message}")
                                continuation.resume(null)
                            }
                        }

                        continuation.invokeOnCancellation {
                            cancellationTokenSource.cancel()
                        }
                    } catch (e: SecurityException) {
                        AppLogger.e("[GmsLocation] No location permission", e)
                        if (continuation.isActive) continuation.resume(null)
                    } catch (e: Exception) {
                        AppLogger.e("[GmsLocation] Unexpected error", e)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }.also { result ->
                if (result == null) AppLogger.w("[GmsLocation] Location request timed out after ${timeoutMs}ms")
            }
        }
    }
}
