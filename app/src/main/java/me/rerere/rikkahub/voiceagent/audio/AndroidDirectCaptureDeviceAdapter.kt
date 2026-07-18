package me.rerere.rikkahub.voiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.voiceagent.RetirementBarrier

internal class AndroidDirectCaptureDeviceAdapter(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectCaptureDeviceCapability {
    @SuppressLint("MissingPermission")
    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        val manager = audioManager ?: return null
        val hasPermission = runCatching { hasBluetoothConnectPermission(context) }
            .onFailure { logWarning("Direct capture route permission check failed", it) }
            .getOrDefault(false)
        if (!hasPermission) {
            logDebug("Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }

        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.onFailure {
            logWarning("Direct capture route enumeration failed", it)
        }.getOrDefault(emptyList())
        val routeDevices = devices.map(AudioDeviceInfo::toVoiceAudioRouteDevice)
        val selectedRoute = selectPreferredCaptureRoute(routeDevices)
        logDebug(
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selectedRoute?.debugLabel() ?: "default"}",
        )
        val selected = selectedRoute?.let { route -> devices.firstOrNull { it.id == route.id } }
            ?: return null

        val preferredAccepted = runCatching { recorder.setPreferredDevice(selected) }
            .onFailure { logWarning("Direct preferred Bluetooth device failed", it) }
            .getOrDefault(false)
        val communicationAccepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.setCommunicationDevice(selected) }
                .onFailure { logWarning("Direct communication route failed", it) }
                .getOrDefault(false)
        } else {
            false
        }
        logDebug(
            "Direct capture route=${selected.toVoiceAudioRouteDevice().debugLabel()} " +
                "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
        )
        if (!communicationAccepted) return null

        val retirement = RetirementBarrier()
        return DirectAudioResourceLease {
            retirement.retire {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    manager.clearCommunicationDevice()
                }
            }
        }
    }
}

private fun AudioDeviceInfo.toVoiceAudioRouteDevice(): VoiceAudioRouteDevice =
    VoiceAudioRouteDevice(
        id = id,
        type = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> VoiceAudioRouteDeviceType.BluetoothSco
            AudioDeviceInfo.TYPE_BLE_HEADSET -> VoiceAudioRouteDeviceType.BluetoothBleHeadset
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> VoiceAudioRouteDeviceType.BuiltInMic
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> VoiceAudioRouteDeviceType.WiredHeadset
            else -> VoiceAudioRouteDeviceType.Other
        },
        name = productName?.toString().orEmpty(),
    )

private fun VoiceAudioRouteDevice.debugLabel(): String =
    "$id:${type.name}:${name.ifBlank { "unnamed" }}"

private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
}

private fun logWarning(message: String, error: Throwable) {
    runCatching { Log.w(TAG, message, error) }
}

private const val TAG = "DirectCaptureDevice"
