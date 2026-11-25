package com.example.core.ai.local

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.ResourceGuard
import com.example.core.ai.api.ResourceSnapshot
import com.example.core.ai.api.ThermalTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors device thermal status, free RAM, battery level, and battery saver state.
 * Only attaches system listeners while the Local AI module is enabled.
 */
class DeviceResourceGuard(context: Context) : ResourceGuard {

  private val appContext = context.applicationContext
  private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private val activityManager =
    appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  private val batteryManager =
    appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

  private val _snapshot = MutableStateFlow(ResourceSnapshot())
  override val snapshot: StateFlow<ResourceSnapshot> = _snapshot.asStateFlow()

  private val _deviceProfile = MutableStateFlow(DeviceProfile())
  override val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile.asStateFlow()

  private var thermalListener: Any? = null
  private var latestSettings = LocalAiSettings()

  override fun startMonitoring(settings: LocalAiSettings) {
    latestSettings = settings
    refreshNow(settings)

    if (!settings.enabled) {
      stopMonitoring()
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener == null && powerManager != null) {
      val listener =
        PowerManager.OnThermalStatusChangedListener { _ ->
          refreshNow(latestSettings)
        }
      thermalListener = listener
      powerManager.addThermalStatusListener(listener)
    }
  }

  override fun stopMonitoring() {
    val pm = powerManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null && pm != null) {
      val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener
      if (listener != null) {
        runCatching { pm.removeThermalStatusListener(listener) }
      }
      thermalListener = null
    }
  }

  override fun refreshNow(settings: LocalAiSettings): ResourceSnapshot {
    latestSettings = settings
    val thermalTier = readThermalTier()
    val memInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memInfo)
    val availRamMb = memInfo.availMem / (1024L * 1024L)
    val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(availRamMb)
    val isLowMem = memInfo.lowMemory
    val isLowRamDevice = activityManager?.isLowRamDevice == true
    val batteryPct =
      batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100)
        ?: 100
    val isPowerSave = powerManager?.isPowerSaveMode == true

    val blockReason =
      evaluatePolicy(
        settings = settings,
        thermalTier = thermalTier,
        isLowMemory = isLowMem,
        isPowerSave = isPowerSave,
        batteryPercent = batteryPct,
      )

    val current =
      ResourceSnapshot(
        thermalTier = thermalTier,
        availableRamMb = availRamMb,
        isLowMemory = isLowMem,
        batteryPercent = batteryPct,
        isPowerSaveMode = isPowerSave,
        canRunInference = blockReason == null,
        blockReason = blockReason,
      )
    _snapshot.value = current
    _deviceProfile.value =
      buildDeviceProfile(
        snapshot = current,
        totalRamMb = totalRamMb,
        isLowRamDevice = isLowRamDevice,
        settings = settings,
      )
    return current
  }

  override fun resolveDeviceProfile(settings: LocalAiSettings): DeviceProfile {
    refreshNow(settings)
    return _deviceProfile.value
  }

  private fun readThermalTier(): ThermalTier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
      return ThermalTier.NORMAL
    }
    return when (powerManager.currentThermalStatus) {
      PowerManager.THERMAL_STATUS_NONE,
      PowerManager.THERMAL_STATUS_LIGHT, -> ThermalTier.NORMAL
      PowerManager.THERMAL_STATUS_MODERATE -> ThermalTier.MODERATE
      PowerManager.THERMAL_STATUS_SEVERE -> ThermalTier.SEVERE
      PowerManager.THERMAL_STATUS_CRITICAL,
      PowerManager.THERMAL_STATUS_EMERGENCY,
      PowerManager.THERMAL_STATUS_SHUTDOWN, -> ThermalTier.CRITICAL
      else -> ThermalTier.NORMAL
    }
  }

  companion object {
    /**
     * Pure resource-gating policy evaluated before starting any local AI workload.
     */
    fun evaluatePolicy(
      settings: LocalAiSettings,
      thermalTier: ThermalTier,
      isLowMemory: Boolean,
      isPowerSave: Boolean,
      batteryPercent: Int,
    ): String? {
      if (!settings.enabled) return "El módulo de IA local está desactivado"
      if (thermalTier.ordinal > settings.maxAllowedThermalTier.ordinal) {
        return "Pausado por protección térmica (${thermalTier.name})"
      }
      if (isLowMemory) return "Memoria RAM insuficiente para análisis local"
      if (isPowerSave && !settings.allowOnBatterySaver) {
        return "Pausado por modo ahorro de batería"
      }
      if (batteryPercent < 12 && !settings.allowOnBatterySaver) {
        return "Batería baja ($batteryPercent%) para procesamiento local"
      }
      return null
    }

    /**
     * Computes a bounded memory & frame-sampling [DeviceProfile] from live RAM and thermal telemetry.
     */
    fun buildDeviceProfile(
      snapshot: ResourceSnapshot,
      totalRamMb: Long,
      isLowRamDevice: Boolean,
      settings: LocalAiSettings,
    ): DeviceProfile {
      val effectiveLowRam = isLowRamDevice || snapshot.isLowMemory || snapshot.availableRamMb < 900L || totalRamMb < 3_200L
      val tier =
        when {
          effectiveLowRam || snapshot.thermalTier >= ThermalTier.MODERATE -> DeviceMemoryTier.CONSERVATIVE
          totalRamMb >= 6_000L && snapshot.availableRamMb >= 2_000L && snapshot.thermalTier == ThermalTier.NORMAL ->
            DeviceMemoryTier.PERFORMANCE
          else -> DeviceMemoryTier.BALANCED
        }

      val allowSampling = snapshot.canRunInference && !snapshot.isLowMemory && snapshot.availableRamMb >= 350L
      val (maxFrames, maxWidth, maxFixes) =
        when (tier) {
          DeviceMemoryTier.CONSERVATIVE -> Triple(if (allowSampling) 3 else 0, 240, 3)
          DeviceMemoryTier.BALANCED -> Triple(if (allowSampling) 5 else 0, 360, 4)
          DeviceMemoryTier.PERFORMANCE -> Triple(if (allowSampling) 8 else 0, 480, 4)
        }

      val profileSuffix =
        if (allowSampling) {
          "$maxFrames frames @ ${maxWidth}px"
        } else {
          "Solo métricas (RAM protegida)"
        }

      return DeviceProfile(
        tier = tier,
        totalRamMb = totalRamMb,
        availableRamMb = snapshot.availableRamMb,
        isLowRamDevice = isLowRamDevice,
        thermalTier = snapshot.thermalTier,
        maxSampledFrames = maxFrames,
        maxAnalysisWidthPx = maxWidth,
        maxSuggestedFixes =
          if (settings.profile == com.example.core.ai.api.LocalModelProfile.LIGHTWEIGHT_DSP) {
            maxFixes.coerceAtMost(3)
          } else {
            maxFixes
          },
        allowFrameSampling = allowSampling,
        summaryLabel = "${tier.label} · $profileSuffix",
      )
    }
  }
}
