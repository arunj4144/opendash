package com.opendash.app.audio

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.opendash.app.settings.AppSettings

/**
 * Routes call audio to the user's preferred Bluetooth device. Android only
 * lets apps control audio routing for calls and their own playback - not
 * for other apps' media (Spotify, YouTube, etc.), so this is intentionally
 * scoped to calls only.
 */
class CallAudioRouter(private val context: Context) {

    fun routeCallToPreferredDevice() {
        val preferredAddress = AppSettings(context).preferredCallAudioDeviceAddress ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // setCommunicationDevice needs API 31+

        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val target = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                device.address.equals(preferredAddress, ignoreCase = true)
        } ?: return
        audioManager.setCommunicationDevice(target)
    }

    fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
        }
    }

    /** Bonded Bluetooth audio devices, for the Settings picker. */
    fun bondedAudioDevices(bondedDevices: Set<BluetoothDevice>): List<BluetoothDevice> {
        return bondedDevices.filter { device ->
            device.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
        }
    }
}
