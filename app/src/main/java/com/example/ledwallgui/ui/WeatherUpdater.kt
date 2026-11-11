package com.example.ledwallgui.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Open-Meteo current conditions. A mode selection forces a fresh location and API lookup;
 * periodic checks thereafter are at least 30 minutes apart. The single in-flight guard is
 * process-wide because several screens can otherwise construct separate WeatherUpdaters.
 */
class WeatherUpdater(context: Context, private val ble: GlobalBluetoothManager) {
    companion object {
        const val POLL_MS = WeatherSchedule.POLL_MS
        private val inFlight = AtomicBoolean(false)
        fun isChecking(): Boolean = inFlight.get()
    }

    private val appContext = context.applicationContext

    /** Always fetch fresh conditions on Weather selection, even if a previous check was recent. */
    fun refreshNow() = schedule(force = true)
    fun updateIfDue() = schedule(force = false)

    private fun schedule(force: Boolean) {
        val prefs = appContext.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("location_enabled", false)) return
        if (!ble.isConnected) return
        if (!force && ble.liveState.value?.mode != "Weather") return
        if (inFlight.get()) return

        val now = System.currentTimeMillis()
        val lastSuccess = prefs.getLong("last_weather_poll_ms", 0L)
        val previousFailed = prefs.contains("weather_last_error")
        if (!force && !previousFailed && lastSuccess > 0 && now - lastSuccess < POLL_MS) {
            // Re-send cached conditions on a reconnect, without contacting Open-Meteo.
            if (prefs.contains("weather_code")) {
                ble.setWeather(prefs.getInt("weather_code", 0),
                    prefs.getInt("weather_wind", 0), prefs.getBoolean("weather_is_day", true))
                updateSunriseLevel()
            }
            return
        }
        if (!WeatherSchedule.due(now, lastSuccess,
                prefs.getLong("last_weather_attempt_ms", 0L), force, previousFailed)) return
        if (!inFlight.compareAndSet(false, true)) return

        prefs.edit().putLong("last_weather_attempt_ms", now).remove("weather_last_error").apply()
        thread(name = "LED-wall-weather", isDaemon = true) {
            try {
                val fine = ContextCompat.checkSelfPermission(appContext,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(appContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) {
                    prefs.edit().putString("weather_last_error", "Location permission needed").apply()
                    return@thread
                }
                val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = locate(lm) ?: run {
                    prefs.edit().putString("weather_last_error", "Waiting for location fix").apply()
                    return@thread
                }

                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
                    "&longitude=${loc.longitude}&current=weather_code,wind_speed_10m,is_day" +
                    "&daily=sunrise&hourly=weather_code&forecast_days=2" +
                    "&wind_speed_unit=kmh&timezone=auto")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 6000
                    connection.readTimeout = 6000
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val current = json.getJSONObject("current")
                    val code = current.getInt("weather_code").coerceIn(0, 99)
                    val wind = current.getDouble("wind_speed_10m").toInt().coerceIn(0, 200)
                    val isDay = current.optInt("is_day", 1) == 1
                    // Parse Open-Meteo's location-local sunrise into an absolute instant.
                    val zone = try { ZoneId.of(json.optString("timezone", "UTC")) }
                        catch (_: Exception) { ZoneId.of("UTC") }
                    // Pick the next relevant sunrise; at night this should be tomorrow,
                    // not today's already-finished sunrise.
                    val sunriseTimes = json.optJSONObject("daily")?.optJSONArray("sunrise")
                    val nowForSunrise = System.currentTimeMillis()
                    val sunriseMs = (0 until (sunriseTimes?.length() ?: 0)).mapNotNull { i ->
                        try {
                            LocalDateTime.parse(sunriseTimes!!.getString(i))
                                .atZone(zone).toInstant().toEpochMilli()
                        } catch (_: Exception) { null }
                    }.firstOrNull { it + SunriseSchedule.AFTER_MS >= nowForSunrise } ?: 0L
                    // Use the weather forecast closest to sunrise, not today's current conditions.
                    val hourly = json.optJSONObject("hourly")
                    val hourTimes = hourly?.optJSONArray("time")
                    val hourCodes = hourly?.optJSONArray("weather_code")
                    var closestHour = Long.MAX_VALUE
                    var sunriseForecastClear = false
                    if (sunriseMs > 0 && hourTimes != null && hourCodes != null) {
                        for (i in 0 until minOf(hourTimes.length(), hourCodes.length())) {
                            val hourMs = try { LocalDateTime.parse(hourTimes.getString(i))
                                .atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { continue }
                            val delta = kotlin.math.abs(hourMs - sunriseMs)
                            if (delta < closestHour) {
                                closestHour = delta
                                sunriseForecastClear = hourCodes.optInt(i, 99) <= 1
                            }
                        }
                    }
                    val label = String.format(Locale.US, "%.2f, %.2f", loc.latitude, loc.longitude)
                    prefs.edit().putLong("last_weather_poll_ms", System.currentTimeMillis())
                        .putString("weather_location_label", label)
                        .putBoolean("weather_is_day", isDay)
                        .putLong("weather_sunrise_ms", sunriseMs)
                        .putBoolean("weather_sunrise_clear", sunriseForecastClear)
                        .putInt("weather_code", code).putInt("weather_wind", wind)
                        .remove("weather_last_error").apply()
                    // Another device might have switched modes while the HTTP request was running.
                    if (ble.isConnected && ble.liveState.value?.mode == "Weather") {
                        ble.setWeather(code, wind, isDay)
                        updateSunriseLevel()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w("LEDWeather", "Weather refresh failed", e)
                prefs.edit().putString("weather_last_error", "Weather service unavailable; retrying").apply()
            } finally {
                inFlight.set(false)
                prefs.edit().putLong("weather_display_refresh_at_ms", System.currentTimeMillis()).apply()
            }
        }
    }

    /** Recalculate every 30s while Weather mode is active, not only at HTTP refresh. */
    fun updateSunriseLevel() {
        if (!ble.isConnected || ble.liveState.value?.mode != "Weather") return
        val prefs = appContext.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val level = SunriseSchedule.level(System.currentTimeMillis(),
            prefs.getLong("weather_sunrise_ms", 0L),
            prefs.getBoolean("weather_sunrise_clear", false))
        ble.setSunriseLevel(level)
    }

    /** getCurrentLocation requests a new fix; a reasonably recent cached fix is the fallback.
     * This waits only on the weather worker thread, never on the tablet UI thread.
     */
    private fun locate(manager: LocationManager): Location? {
        val available = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider -> try { manager.isProviderEnabled(provider) } catch (_: Exception) { false } }
        val cached = available.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        }.filter { System.currentTimeMillis() - it.time in 0L..(24L * 60L * 60_000L) }
            .maxByOrNull { it.time }
        val provider = available.firstOrNull() ?: return cached
        val latch = CountDownLatch(1)
        val found = AtomicReference<Location?>(null)
        val signal = CancellationSignal()
        try {
            manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                found.set(location)
                latch.countDown()
            }
            latch.await(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.d("LEDWeather", "Fresh location unavailable; using recent location if available", e)
        } finally {
            signal.cancel()
        }
        return found.get() ?: cached
    }
}
