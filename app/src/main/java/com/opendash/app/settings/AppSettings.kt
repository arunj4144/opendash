package com.opendash.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single place for all persisted app configuration: bonded bike address,
 * Gemini API key, notification source apps, nav-app override, and preferred
 * call-audio device. Backed by EncryptedSharedPreferences since the Gemini
 * key is a secret worth protecting at rest.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opendash_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Plain (unencrypted) prefs for non-secret pairing state. EncryptedSharedPreferences
    // is deprecated and prone to silent corruption/reset on some devices; the
    // "have we paired this bike before" flag MUST persist reliably, because if it
    // doesn't the app re-triggers the bike's physical "add device" prompt on every
    // reconnect and the handshake stalls at "handshaking". None of this is secret.
    private val pairingPrefs: SharedPreferences =
        context.getSharedPreferences("opendash_pairing", Context.MODE_PRIVATE)

    var bondedDeviceAddress: String?
        get() = prefs.getString(KEY_BONDED_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_BONDED_ADDRESS, value).apply()

    var bondedDeviceName: String?
        get() = prefs.getString(KEY_BONDED_NAME, null)
        set(value) = prefs.edit().putString(KEY_BONDED_NAME, value).apply()

    var geminiApiKey: String?
        get() = prefs.getString(KEY_GEMINI_KEY, null)
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value).apply()

    /** Gemini model used for notification summaries (see [GEMINI_MODELS]). */
    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    /** Whether the first-run onboarding (name + permissions) has been completed. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    /** Used to personalize the test notification and greeting text ("Hey <name>"). */
    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    /** Package names of apps whose notifications should be mirrored to the dash. */
    var notificationSourceApps: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFICATION_APPS, DEFAULT_NOTIFICATION_APPS) ?: DEFAULT_NOTIFICATION_APPS
        set(value) = prefs.edit().putStringSet(KEY_NOTIFICATION_APPS, value).apply()

    /** Null = auto-detect via CATEGORY_NAVIGATION notifications. Non-null = manual override package name. */
    var navAppOverride: String?
        get() = prefs.getString(KEY_NAV_APP_OVERRIDE, null)
        set(value) = prefs.edit().putString(KEY_NAV_APP_OVERRIDE, value).apply()

    var preferredCallAudioDeviceAddress: String?
        get() = prefs.getString(KEY_CALL_AUDIO_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_CALL_AUDIO_DEVICE, value).apply()

    /** Quick-mute: when false, non-navigation notifications are not pushed to the dash. */
    var mirrorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MIRROR_ENABLED, value).apply()

    /** Whether the dash notification banner scrolls (marquee) when text overflows. */
    var marqueeEnabled: Boolean
        get() = prefs.getBoolean(KEY_MARQUEE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MARQUEE_ENABLED, value).apply()

    /**
     * When on, the handlebar remote acts as a system-wide gamepad: while any
     * OTHER app is in front, UP/DOWN move focus, SET clicks, BACK goes back -
     * driven through the accessibility service. When OpenDash itself is in
     * front, the remote drives OpenDash's own UI as before. Requires the user to
     * enable OpenDash's accessibility service in Android Settings.
     */
    var gamepadEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_GAMEPAD_ENABLED, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_GAMEPAD_ENABLED, value).apply()

    /**
     * Whether we've ever completed the BCCU handshake with this bike before.
     * The bike decides whether to show its physical "confirm new pairing"
     * prompt based on whether our reply to its cmd=HELLO is "I don't know you"
     * (cmd 0) or "I know you, just refresh keys" (cmd 1) - so this flag, not
     * the in-memory session key pool (which is intentionally re-derived every
     * connection per protocol), is what must persist across app/service
     * restarts to avoid re-prompting on every reconnect.
     */
    fun hasPairedBefore(deviceAddress: String): Boolean {
        val key = KEY_PAIRED_PREFIX + deviceAddress.uppercase()
        // Check the reliable plain store first, then fall back to the legacy
        // encrypted store so bikes paired by older builds aren't re-prompted.
        return pairingPrefs.getBoolean(key, false) ||
            runCatching { prefs.getBoolean(key, false) }.getOrDefault(false)
    }

    fun markPairedBefore(deviceAddress: String) {
        // commit() (synchronous) so the flag is on disk before the service can be
        // torn down on the disconnect that often follows authentication.
        pairingPrefs.edit().putBoolean(KEY_PAIRED_PREFIX + deviceAddress.uppercase(), true).commit()
    }

    /**
     * Forget that we've paired this bike, so the next handshake replies HELLO
     * (cmd 0) and the dash shows its physical "add device" prompt to establish
     * fresh keys. Required when the dash has lost its side of the pairing:
     * otherwise we keep replying GENERATE_KEYS against a dash that no longer
     * knows us and the handshake stalls forever at "handshaking". Clears both
     * the plain and legacy-encrypted stores.
     */
    fun clearPairedBefore(deviceAddress: String) {
        val key = KEY_PAIRED_PREFIX + deviceAddress.uppercase()
        pairingPrefs.edit().remove(key).commit()
        runCatching { prefs.edit().remove(key).commit() }
    }

    /** Manual hardware-calibration result for one icon, from the Symbol Testing screen. "works", "wrong", or null (untested). */
    fun getIconTestResult(key: String): String? = prefs.getString(KEY_ICON_TEST_PREFIX + key, null)

    fun setIconTestResult(key: String, result: String) {
        prefs.edit().putString(KEY_ICON_TEST_PREFIX + key, result).apply()
    }

    /**
     * User-supplied turn-icon calibration: maps a captured notification-icon
     * bitmap hash to the correct dash TurnIcon name. Because Google Maps reuses
     * byte-identical bitmaps per maneuver, one label makes that maneuver exact
     * forever (U-turns, roundabouts, etc. that the pixel heuristic can't infer).
     * Stored in plain prefs - not secret, and must persist reliably.
     */
    fun getTurnCalibration(hash: Int): String? = pairingPrefs.getString(KEY_TURN_CAL_PREFIX + hash, null)

    fun setTurnCalibration(hash: Int, iconName: String) {
        pairingPrefs.edit().putString(KEY_TURN_CAL_PREFIX + hash, iconName).apply()
    }

    fun clearTurnCalibration(hash: Int) {
        pairingPrefs.edit().remove(KEY_TURN_CAL_PREFIX + hash).apply()
    }

    companion object {
        private const val KEY_BONDED_ADDRESS = "bonded_device_address"
        private const val KEY_BONDED_NAME = "bonded_device_name"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"

        const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"

        /** Selectable Gemini models for notification summaries. */
        val GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )
        private const val KEY_NOTIFICATION_APPS = "notification_source_apps"
        private const val KEY_NAV_APP_OVERRIDE = "nav_app_override"
        private const val KEY_CALL_AUDIO_DEVICE = "call_audio_device"
        private const val KEY_MIRROR_ENABLED = "mirror_enabled"
        private const val KEY_MARQUEE_ENABLED = "marquee_enabled"
        private const val KEY_GAMEPAD_ENABLED = "gamepad_enabled"
        private const val KEY_PAIRED_PREFIX = "paired_before_"
        private const val KEY_ICON_TEST_PREFIX = "icon_test_"
        private const val KEY_TURN_CAL_PREFIX = "turncal_"

        val DEFAULT_NOTIFICATION_APPS = setOf(
            "com.whatsapp",
            "com.google.android.apps.messaging"
        )

        val KNOWN_NAV_APPS = setOf(
            "com.google.android.apps.maps",
            "com.waze",
            "com.here.app.maps",
            "com.sygic.aura",
            "net.osmand",
            "com.kurviger.app"
        )
    }
}
