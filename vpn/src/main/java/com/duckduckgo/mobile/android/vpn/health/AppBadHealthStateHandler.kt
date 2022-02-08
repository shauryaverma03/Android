/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.health

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.appbuildconfig.api.BuildFlavor
import com.duckduckgo.appbuildconfig.api.BuildFlavor.INTERNAL
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.mobile.android.vpn.model.AppHealthState
import com.duckduckgo.mobile.android.vpn.model.HealthEventType.BAD_HEALTH
import com.duckduckgo.mobile.android.vpn.model.HealthEventType.GOOD_HEALTH
import com.duckduckgo.mobile.android.vpn.pixels.DeviceShieldPixels
import com.duckduckgo.mobile.android.vpn.service.TrackerBlockingVpnService
import com.duckduckgo.mobile.android.vpn.store.AppHealthDatabase
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.moshi.Moshi
import dagger.SingleInstanceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneOffset
import org.threeten.bp.format.DateTimeFormatter
import timber.log.Timber
import javax.inject.Inject

@ContributesMultibinding(
    scope = AppScope::class,
    boundType = AppHealthCallback::class
)
@ContributesBinding(
    scope = AppScope::class,
    boundType = BadHealthMitigationFeature::class
)
@SingleInstanceIn(AppScope::class)
class AppBadHealthStateHandler @Inject constructor(
    private val context: Context,
    private val appBuildConfig: AppBuildConfig,
    private val appHealthDatabase: AppHealthDatabase,
    private val deviceShieldPixels: DeviceShieldPixels,
    private val dispatcherProvider: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : AppHealthCallback, BadHealthMitigationFeature {

    private var debounceJob = ConflatedJob()

    private val preferences: SharedPreferences
        get() = context.getSharedPreferences(FILENAME, Context.MODE_PRIVATE)

    private var backoffIncrement: Long
        get() = preferences.getLong("backoff", INITIAL_BACKOFF)
        set(value) {
            preferences.edit { putLong("backoff", value) }
        }

    private var restartBoundary: String?
        get() = preferences.getString("restartBoundary", null)
        set(value) {
            preferences.edit { putString("restartBoundary", value) }
        }

    override var isEnabled: Boolean
        get() = preferences.getBoolean("isEnabled", true)
        set(value) {
            preferences.edit { putBoolean("isEnabled", value) }
        }

    override suspend fun onAppHealthUpdate(appHealthData: AppHealthData): Boolean {
        if (!isEnabled) {
            Timber.d("Feature is disabled, skipping mitigation")
            return false
        }

        return withContext(dispatcherProvider.io()) {
            if (appHealthData.alerts.isNotEmpty()) {
                // send first-in-day pixels for alerts so what we can gather how many users see a particular alert every day
                sendFirstInDayAlertPixels(appHealthData.alerts)

                // we don't include raw metrics marked as "redacted" as they can contain information that could
                // be used to fingerprint.
                val (badHealthMetrics, _) = appHealthData.systemHealth.rawMetrics.partition {
                    (it.isInBadHealth() || it.informational) && !it.redacted
                }
                val badHealthData = appHealthData.copy(systemHealth = appHealthData.systemHealth.copy(rawMetrics = badHealthMetrics))
                val jsonAdapter = Moshi.Builder().build().run {
                    adapter(AppHealthData::class.java)
                }

                val json = jsonAdapter.toJson(badHealthData)

                Timber.v("Storing app health alerts in local store: $badHealthData")

                val shouldRestartVpn = shouldRestartVpn(json)
                val restartLocaltime = if (shouldRestartVpn) {
                    LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                } else {
                    appHealthDatabase.appHealthDao().latestHealthStateByType(BAD_HEALTH)?.restartedAtEpochSeconds
                }
                appHealthDatabase.appHealthDao().insert(
                    AppHealthState(
                        type = BAD_HEALTH,
                        alerts = appHealthData.alerts,
                        healthDataJsonString = json,
                        restartedAtEpochSeconds = restartLocaltime
                    )
                )

                if (shouldRestartVpn) restartVpn()

                return@withContext shouldRestartVpn
            } else {
                // first send the pixel
                sendPixelIfBadHealthResolved(appHealthDatabase.appHealthDao().latestHealthState())
                // then store
                appHealthDatabase.appHealthDao().insert(
                    AppHealthState(type = GOOD_HEALTH, alerts = listOf(), healthDataJsonString = "", restartedAtEpochSeconds = null)
                )
                resetBackoff()
                Timber.d("No alerts")
                return@withContext false
            }
        }
    }

    private fun resetBackoff() {
        backoffIncrement = INITIAL_BACKOFF
        restartBoundary = null
        Timber.d("Reset backoff, restartBoundary = $restartBoundary")
    }

    private fun sendFirstInDayAlertPixels(alerts: List<String>) {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            alerts.forEach { alert ->
                deviceShieldPixels.sendHealthMonitorAlert(alert)
            }
        }
    }

    private fun shouldRestartVpn(json: String): Boolean {
        val boundary = restartBoundary
        return if (isVpnRestartAllowed(boundary)) {
            Timber.v("Restarting the VPN...")

            // update the restart boundary
            backoffIncrement = if (backoffIncrement == 0L) INITIAL_BACKOFF_INCREMENT_SECONDS else backoffIncrement * 2
            DATE_FORMATTER.format(LocalDateTime.now().plusSeconds(backoffIncrement)).run {
                restartBoundary = this
            }

            Timber.v("backoff = $backoffIncrement, boundary = $restartBoundary")

            debouncedPixelBadHealth(json, restarted = true)
            true
        } else {
            Timber.v("Cancelled VPN restart, backoff boundary ($boundary)...")
            debouncedPixelBadHealth(json)
            false
        }
    }

    private suspend fun restartVpn() {
        // place this in a different job to ensure the restart completes successfully and nobody can cancel it by mistake
        appCoroutineScope.launch {
            deviceShieldPixels.didRestartVpnOnBadHealth()
            TrackerBlockingVpnService.restartVpnService(context, forceGc = true)
        }.join()
    }

    private fun isVpnRestartAllowed(boundary: String?): Boolean {
        val now = DATE_FORMATTER.format(LocalDateTime.now())
        Timber.d("Checking if should restart VPN, boundary = $boundary")
        return (boundary == null || now >= boundary)
    }

    private fun debouncedPixelBadHealth(
        badHealthJsonString: String,
        restarted: Boolean = false
    ) {
        if (debounceJob.isActive) {
            Timber.v("debouncing bad health pixel firing")
            return
        }
        // Place this in a different job (form the app scope) to make sure it is sent
        // Debounced it to deduplicate pixels as if the VPN is restarted, we'll get immediately a call to onAppHealthUpdate() with same bad-health
        debounceJob += appCoroutineScope.launch(dispatcherProvider.io()) {
            val encodedData = Base64.encodeToString(
                badHealthJsonString.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
            )
            deviceShieldPixels.sendHealthMonitorReport(
                mapOf(
                    MANUFACTURER_KEY to appBuildConfig.manufacturer,
                    MODEL_KEY to if (appBuildConfig.flavor.isInternal()) appBuildConfig.model else "redacted",
                    OS_KEY to appBuildConfig.sdkInt.toString(),
                    RESTARTED_KEY to restarted.toString(),
                    BAD_HEALTH_DATA_KEY to encodedData,
                )
            )
            delay(1000)
        }
    }

    private fun sendPixelIfBadHealthResolved(lastState: AppHealthState?) {
        if (lastState == null || lastState.type == GOOD_HEALTH) return

        // if restarted occurred less than RESTART_BAKE_TIME_SECONDS ago we consider it resolved the issue
        val nowEpochSeconds = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        val restartedWhenEpochSeconds = lastState.restartedAtEpochSeconds ?: Long.MAX_VALUE
        val resolvedByRestart = (nowEpochSeconds - restartedWhenEpochSeconds) < RESTART_BAKE_TIME_SECONDS

        val encodedData = Base64.encodeToString(
            lastState.healthDataJsonString.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )

        if (resolvedByRestart) {
            deviceShieldPixels.badHealthResolvedByRestart(
                mapOf(
                    MANUFACTURER_KEY to appBuildConfig.manufacturer,
                    MODEL_KEY to if (appBuildConfig.flavor.isInternal()) appBuildConfig.model else "redacted",
                    OS_KEY to appBuildConfig.sdkInt.toString(),
                    RESOLVED_BAD_HEALTH_DATA_KEY to encodedData,
                )
            )
        } else {
            deviceShieldPixels.badHealthResolvedItself(
                mapOf(
                    MANUFACTURER_KEY to appBuildConfig.manufacturer,
                    MODEL_KEY to if (appBuildConfig.flavor.isInternal()) appBuildConfig.model else "redacted",
                    OS_KEY to appBuildConfig.sdkInt.toString(),
                    RESOLVED_BAD_HEALTH_DATA_KEY to encodedData,
                )
            )
        }
    }

    private fun BuildFlavor.isInternal(): Boolean {
        return this == INTERNAL
    }

    companion object {
        private const val FILENAME = "com.duckduckgo.mobile.android.vpn.app.health.state"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private const val INITIAL_BACKOFF: Long = 0
        private const val INITIAL_BACKOFF_INCREMENT_SECONDS: Long = 30
        private const val RESTART_BAKE_TIME_SECONDS = 45

        private const val MANUFACTURER_KEY = "manufacturer"
        private const val MODEL_KEY = "model"
        private const val OS_KEY = "os"
        private const val RESTARTED_KEY = "restarted"
        private const val BAD_HEALTH_DATA_KEY = "badHealthData"
        private const val RESOLVED_BAD_HEALTH_DATA_KEY = "resolvedBadHealthData"
    }
}