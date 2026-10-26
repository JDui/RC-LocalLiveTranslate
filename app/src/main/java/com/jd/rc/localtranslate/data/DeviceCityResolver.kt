package com.jd.rc.localtranslate.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class CityLookupState(val busy: Boolean = false, val message: String? = null)

/** User-initiated foreground lookup. Coordinates never enter settings, diagnostics or session data. */
class DeviceCityResolver(private val context: Context) {
    @Suppress("MissingPermission")
    suspend fun resolve(): String? = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || !Geocoder.isPresent()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return@withContext null
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
            .sortedBy { if (it == LocationManager.NETWORK_PROVIDER) 0 else 1 }
        val cached = providers.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        }.filter { isRecent(it, SystemClock.elapsedRealtimeNanos()) }.maxByOrNull { it.elapsedRealtimeNanos }
        val location = cached ?: withTimeoutOrNull(10_000) {
            var fix: Location? = null
            for (provider in providers) {
                fix = current(manager, provider)
                if (fix != null && isRecent(fix, SystemClock.elapsedRealtimeNanos())) break
                fix = null
            }
            fix
        } ?: return@withContext null
        withTimeoutOrNull(8_000) {
            val coder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                coder.getFromLocation(location.latitude, location.longitude, 3, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.toList())
                    }
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            } else runInterruptible {
                @Suppress("DEPRECATION")
                coder.getFromLocation(location.latitude, location.longitude, 3).orEmpty()
            }
            addresses.firstNotNullOfOrNull(::cityFromAddress)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String): Location? =
        withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    signal.cancel()
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    signal.cancel()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    companion object {
        internal fun isRecent(location: Location, nowNanos: Long): Boolean =
            location.elapsedRealtimeNanos > 0 && nowNanos - location.elapsedRealtimeNanos in 0..600_000_000_000L

        private val municipalities = setOf("北京", "北京市", "上海", "上海市", "天津", "天津市", "重庆", "重庆市",
            "香港", "香港特别行政区", "澳门", "澳门特别行政区")

        internal fun cityFromAddress(address: Address): String? =
            sequenceOf(address.adminArea?.takeIf { it in municipalities }, address.locality, address.subAdminArea).filterNotNull()
                .map(SessionTitle::normalizeCity).firstOrNull { it.isNotBlank() }
    }
}
