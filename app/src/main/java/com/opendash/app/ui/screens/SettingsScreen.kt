package com.opendash.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendash.app.ble.BccuConnectionService
import com.opendash.app.ble.BccuProtocol
import com.opendash.app.settings.AppSettings
import com.opendash.app.ui.components.Eyebrow
import com.opendash.app.ui.components.GroupCard
import com.opendash.app.ui.components.KtmToggle
import com.opendash.app.ui.components.MonoValue
import com.opendash.app.ui.components.SettingsRow
import com.opendash.app.ui.theme.Barlow
import com.opendash.app.ui.theme.BarlowCondensed
import com.opendash.app.ui.theme.Ktm
import com.opendash.app.ui.theme.OpenDashIcons

private enum class SettingsDialog { NONE, NAME, GEMINI, NAV_APP, MIRROR_APPS, CALL_AUDIO }

/**
 * Settings (screen 05) — the §5 restructure into four labelled groups
 * (Connection, Notifications, Navigation, Diagnostics). Every capability from
 * the original flat list is preserved: name, Gemini key, nav detection +
 * manual override, mirror-from apps picker, call-audio device, battery
 * optimization, logs, test notification, test guidance, symbol testing, build
 * version — plus the new Re-pair, quick-mute and marquee toggles.
 */
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSymbolTest: () -> Unit,
    onOpenTurnCalibration: () -> Unit,
    onRepair: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager

    var geminiKey by remember { mutableStateOf(settings.geminiApiKey ?: "") }
    var userName by remember { mutableStateOf(settings.userName ?: "") }
    var navAutoDetect by remember { mutableStateOf(settings.navAppOverride == null) }
    var navOverride by remember { mutableStateOf(settings.navAppOverride) }
    var sourceApps by remember { mutableStateOf(settings.notificationSourceApps) }
    var preferredAudioAddress by remember { mutableStateOf(settings.preferredCallAudioDeviceAddress) }
    var mirrorEnabled by remember { mutableStateOf(settings.mirrorEnabled) }
    var marqueeEnabled by remember { mutableStateOf(settings.marqueeEnabled) }
    var gamepadEnabled by remember { mutableStateOf(settings.gamepadEnabled) }
    var accessibilityGranted by remember {
        mutableStateOf(com.opendash.app.controller.RemoteControlAccessibilityService.isRunning)
    }
    var dialog by remember { mutableStateOf(SettingsDialog.NONE) }

    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }
    val bondedAudioDevices = remember {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        adapter?.bondedDevices?.filter {
            it.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
        } ?: emptyList()
    }

    var notificationAccessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                accessibilityGranted = com.opendash.app.controller.RemoteControlAccessibilityService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connectionState by BccuConnectionService.connectionState.collectAsState()
    val connected = connectionState == BccuConnectionService.ConnectionState.AUTHENTICATED
    val vehicleName = settings.bondedDeviceName ?: "Not paired"

    fun appLabel(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .background(Ktm.Screen),
    ) {
        // Header: back + title.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                OpenDashIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.TextSecondary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(22.dp),
            )
            Text(
                "SETTINGS", color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 28.sp, letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===== Connection =====
            item {
                GroupCard("Connection") {
                    SettingsRow("Vehicle", showDivider = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (connected) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Ktm.Green))
                                androidx.compose.foundation.layout.Spacer(Modifier.size(7.dp))
                            }
                            MonoValue(vehicleName, color = if (connected) Ktm.Green else Ktm.Dim)
                        }
                    }
                    SettingsRow("Re-pair vehicle", showDivider = true, onClick = onRepair) {
                        OutlinedPill("RE-PAIR")
                    }
                    SettingsRow("Your name", showDivider = true, onClick = { dialog = SettingsDialog.NAME }) {
                        MonoValue(userName.ifBlank { "Set ›" })
                    }
                    SettingsRow("Gemini API key", showDivider = false, onClick = { dialog = SettingsDialog.GEMINI }) {
                        MonoValue(maskKey(geminiKey))
                    }
                }
            }

            // ===== Notifications =====
            item {
                GroupCard("Notifications") {
                    SettingsRow(
                        "Notification access", showDivider = true,
                        onClick = if (notificationAccessGranted) null else ({
                            context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }),
                    ) {
                        MonoValue(
                            if (notificationAccessGranted) "Granted" else "Grant ›",
                            color = if (notificationAccessGranted) Ktm.Green else Ktm.Orange,
                        )
                    }
                    SettingsRow("Mirror-from apps", showDivider = true, onClick = { dialog = SettingsDialog.MIRROR_APPS }) {
                        MonoValue("${sourceApps.size} selected ›")
                    }
                    SettingsRow("Mirror to dash (quick-mute)", showDivider = true) {
                        KtmToggle(mirrorEnabled, { mirrorEnabled = it; settings.mirrorEnabled = it })
                    }
                    SettingsRow("Marquee scroll", showDivider = false) {
                        KtmToggle(marqueeEnabled, { marqueeEnabled = it; settings.marqueeEnabled = it })
                    }
                }
            }

            // ===== Navigation =====
            item {
                GroupCard("Navigation") {
                    SettingsRow("Auto-detect nav app", showDivider = true) {
                        KtmToggle(navAutoDetect, { on ->
                            navAutoDetect = on
                            if (on) { navOverride = null; settings.navAppOverride = null }
                            else { dialog = SettingsDialog.NAV_APP }
                        })
                    }
                    if (!navAutoDetect) {
                        SettingsRow("Nav app", showDivider = true, onClick = { dialog = SettingsDialog.NAV_APP }) {
                            MonoValue((navOverride?.let { appLabel(it) } ?: "Choose") + " ›")
                        }
                    }
                    SettingsRow("Call-audio device", showDivider = false, onClick = { dialog = SettingsDialog.CALL_AUDIO }) {
                        val label = bondedAudioDevices.firstOrNull { it.address == preferredAudioAddress }
                            ?.let { it.name ?: it.address } ?: "None"
                        MonoValue("$label ›")
                    }
                }
            }

            // ===== Handlebar gamepad =====
            item {
                GroupCard("Handlebar gamepad") {
                    SettingsRow("Control any app with the remote", showDivider = true) {
                        KtmToggle(gamepadEnabled, { on ->
                            gamepadEnabled = on
                            settings.gamepadEnabled = on
                            // Turning it on with the service not yet enabled: send
                            // the rider straight to the accessibility settings.
                            if (on && !accessibilityGranted) {
                                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        })
                    }
                    SettingsRow(
                        "Accessibility service", showDivider = true,
                        onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    ) {
                        MonoValue(
                            if (accessibilityGranted) "Enabled" else "Enable ›",
                            color = if (accessibilityGranted) Ktm.Green else Ktm.Orange,
                        )
                    }
                    SettingsRow("How it works", showDivider = false) {
                        Text(
                            "Up/Down move · Set taps · Back",
                            color = Ktm.Dim, fontFamily = BarlowCondensed, fontSize = 12.sp,
                        )
                    }
                }
            }

            // ===== Diagnostics =====
            item {
                GroupCard("Diagnostics") {
                    SettingsRow("View & share logs", showDivider = true, onClick = onOpenLogs) {
                        Text("›", color = Ktm.Dim, fontSize = 18.sp)
                    }
                    SettingsRow("Symbol testing", showDivider = true, onClick = onOpenSymbolTest) {
                        Text("›", color = Ktm.Dim, fontSize = 18.sp)
                    }
                    SettingsRow("Turn icon calibration", showDivider = true, onClick = onOpenTurnCalibration) {
                        Text("›", color = Ktm.Dim, fontSize = 18.sp)
                    }
                    SettingsRow(
                        "Send test notification", showDivider = true,
                        onClick = {
                            BccuConnectionService.sendNotificationIfRunning(
                                "Hey ${userName.ifBlank { "there" }}",
                                BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT,
                            )
                        },
                    ) { Text("SEND", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
                    SettingsRow(
                        "Send test guidance", showDivider = true,
                        onClick = {
                            BccuConnectionService.sendTurnIconIfRunning(BccuProtocol.TurnIcon.GO_STRAIGHT)
                            BccuConnectionService.sendGuidanceIfRunning("50 m", "Test Road")
                        },
                    ) { Text("SEND", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
                    SettingsRow(
                        "Battery optimization", showDivider = true,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val powerManager = context.getSystemService(PowerManager::class.java)
                                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                    context.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    ) { Text("›", color = Ktm.Dim, fontSize = 18.sp) }
                    SettingsRow("Build", showDivider = false) {
                        MonoValue(com.opendash.app.BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
    }

    // ===== Dialogs =====
    when (dialog) {
        SettingsDialog.NAME -> TextFieldDialog(
            title = "Your name", initial = userName, label = "Name",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { userName = it; settings.userName = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.GEMINI -> TextFieldDialog(
            title = "Gemini API key", initial = geminiKey, label = "API key",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { geminiKey = it; settings.geminiApiKey = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.NAV_APP -> SingleChoiceDialog(
            title = "Navigation app",
            options = AppSettings.KNOWN_NAV_APPS.toList(),
            labelFor = { appLabel(it) },
            selected = navOverride,
            onDismiss = {
                // Cancelling with nothing chosen falls back to auto-detect.
                if (navOverride == null) navAutoDetect = true
                dialog = SettingsDialog.NONE
            },
            onSelect = {
                navOverride = it; settings.navAppOverride = it; navAutoDetect = false
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.CALL_AUDIO -> SingleChoiceDialog(
            title = "Call-audio device",
            options = bondedAudioDevices.map { it.address },
            labelFor = { addr -> bondedAudioDevices.firstOrNull { it.address == addr }?.let { it.name ?: it.address } ?: addr },
            selected = preferredAudioAddress,
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = { preferredAudioAddress = it; settings.preferredCallAudioDeviceAddress = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.MIRROR_APPS -> MultiChoiceDialog(
            title = "Mirror-from apps",
            options = installedApps.map { it.packageName },
            labelFor = { appLabel(it) },
            selected = sourceApps,
            onDismiss = { dialog = SettingsDialog.NONE },
            onToggle = { pkg, checked ->
                sourceApps = if (checked) sourceApps + pkg else sourceApps - pkg
                settings.notificationSourceApps = sourceApps
            },
        )
        SettingsDialog.NONE -> {}
    }
}

private fun maskKey(key: String): String = when {
    key.isBlank() -> "Not set ›"
    key.length <= 4 -> "•••• ›"
    else -> "••••••" + key.takeLast(4)
}

@Composable
private fun OutlinedPill(text: String) {
    Text(
        text, color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, Ktm.Orange, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun TextFieldDialog(
    title: String, initial: String, label: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        textContentColor = Ktm.TextPrimary,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("SAVE", color = Ktm.Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Ktm.Dim) } },
    )
}

@Composable
private fun <T> SingleChoiceDialog(
    title: String, options: List<T>, labelFor: (T) -> String, selected: T?,
    onDismiss: () -> Unit, onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            if (options.isEmpty()) {
                Text("Nothing available.", color = Ktm.Dim, fontFamily = Barlow)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected == option) { onSelect(option) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == option, onClick = { onSelect(option) },
                                colors = RadioButtonDefaults.colors(selectedColor = Ktm.Orange, unselectedColor = Ktm.Dim),
                            )
                            Text(labelFor(option), color = Ktm.TextPrimary, fontFamily = Barlow)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE", color = Ktm.Orange) } },
    )
}

@Composable
private fun <T> MultiChoiceDialog(
    title: String, options: List<T>, labelFor: (T) -> String, selected: Set<T>,
    onDismiss: () -> Unit, onToggle: (T, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val checked = option in selected
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggle(option, !checked) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked, onCheckedChange = { onToggle(option, it) },
                            colors = CheckboxDefaults.colors(checkedColor = Ktm.Orange, uncheckedColor = Ktm.Dim),
                        )
                        Text(labelFor(option), color = Ktm.TextPrimary, fontFamily = Barlow)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE", color = Ktm.Orange) } },
    )
}
