package dev.abode.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.speech.SpeechRecognizer

data class VoiceCapabilities(
    val systemAvailable: Boolean,
    val isLowRamDevice: Boolean,
    val totalRamGb: Float,
    val cores: Int,
    val freeDiskMb: Long,
    val canOfferOnDeviceModel: Boolean,
    val recommended: VoiceTier,
    val reason: String,
)

/**
 * Decides which tiers are OFFERED, not which tier is silently used.
 *
 * Specs are a proxy. The real gate is a micro-benchmark run on the device; this
 * probe is what decides whether it is worth asking the user at all. A cheap
 * phone with a good NPU beats an expensive one with a bad thermal profile, so
 * treat SOC_MODEL as a hint and the benchmark as the answer.
 */
object CapabilityProbe {

    private const val MIN_RAM_GB = 6.0f
    private const val MIN_CORES = 4
    private const val MIN_FREE_DISK_MB = 1024L

    fun probe(context: Context): VoiceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

        val totalRamGb = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        val freeDiskMb = runCatching {
            StatFs(context.noBackupFilesDir.absolutePath).availableBytes / (1024 * 1024)
        }.getOrDefault(0L)

        val systemAvailable = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)

        val canOfferModel = !activityManager.isLowRamDevice &&
            totalRamGb >= MIN_RAM_GB &&
            cores >= MIN_CORES &&
            freeDiskMb >= MIN_FREE_DISK_MB

        val recommended = when {
            systemAvailable -> VoiceTier.SYSTEM
            canOfferModel -> VoiceTier.ON_DEVICE_MODEL
            else -> VoiceTier.CLOUD
        }

        val reason = when {
            systemAvailable -> "System recogniser available; no download needed."
            canOfferModel -> "No system recogniser; this device can run a local model."
            else -> "No system recogniser and the hardware is too small for a local model."
        }

        return VoiceCapabilities(
            systemAvailable = systemAvailable,
            isLowRamDevice = activityManager.isLowRamDevice,
            totalRamGb = totalRamGb,
            cores = cores,
            freeDiskMb = freeDiskMb,
            canOfferOnDeviceModel = canOfferModel,
            recommended = recommended,
            reason = reason,
        )
    }

    /** SoC label, API 31+. Empty on older versions. */
    fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
}
