package com.opendash.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.opendash.app.audio.CallAudioRouter
import com.opendash.app.audio.MediaControlBridge
import com.opendash.app.ble.BccuConnectionService
import com.opendash.app.ble.BccuProtocol
import com.opendash.app.controller.ControllerScreen
import com.opendash.app.controller.ControllerStateMachine
import com.opendash.app.logging.AppLogger
import com.opendash.app.notifications.NotificationRepository
import com.opendash.app.settings.AppSettings
import com.opendash.app.telephony.CallStateMonitor
import com.opendash.app.ui.screens.DirectionScreen
import com.opendash.app.ui.screens.GridMenuScreen
import com.opendash.app.ui.screens.NotificationScreen
import com.opendash.app.ui.screens.PairingScreen
import com.opendash.app.ui.screens.SettingsScreen
import com.opendash.app.ui.theme.OpenDashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppRoute { ONBOARDING, PAIRING, MAIN, SETTINGS, LOGS, SYMBOL_TEST, TURN_CALIBRATION }

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var mediaControl: MediaControlBridge
    private lateinit var callAudioRouter: CallAudioRouter
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var stateMachine: ControllerStateMachine
    private lateinit var actions: ControllerStateMachine.Actions

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* best-effort; features degrade gracefully if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge-to-edge from the very first frame so the layout fills the
        // screen immediately instead of settling once the window insets arrive
        // (each screen applies systemBarsPadding() to keep content clear of the
        // status/nav bars). Without this the UI visibly "jumped" on launch.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settings = AppSettings(this)
        mediaControl = MediaControlBridge(this)
        callAudioRouter = CallAudioRouter(this)

        // First-run users grant permissions with context in the onboarding flow;
        // returning users get a silent top-up request for anything since revoked.
        if (settings.onboardingComplete) requestRuntimePermissions()

        // Auto-connect: if we already have a bonded bike, bring the connection
        // service up as soon as the app is opened (it's a foreground service and
        // owns its own reconnect loop from there on). The AutoConnectReceiver
        // covers the "app never opened" cases (boot, bike back in range).
        settings.bondedDeviceAddress?.let { BccuConnectionService.start(this, it) }

        var callRingingState = mutableStateOf(false)
        actions = object : ControllerStateMachine.Actions {
            override fun playPauseMedia() = mediaControl.playPause()
            override fun nextTrack() = mediaControl.next()
            override fun previousTrack() = mediaControl.previous()
            override fun answerCall() {
                callStateMonitor.answer()
                callAudioRouter.routeCallToPreferredDevice()
            }
            override fun rejectCall() = callStateMonitor.silenceRinger()
            override fun minimizeToHome() { moveTaskToBack(true) }
            override fun openMaps() {
                val pkg = NotificationRepository.currentNavPackage.value
                    ?: settings.navAppOverride
                val launchIntent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
                }
            }
            override fun bringAppToForeground() {
                if (AppForegroundState.isForeground) return
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            override fun showWatchdogWarning() {
                // NOTIFICATION_WAYPOINT is confirmed rendering on real hardware;
                // WARNING is confirmed NOT to render - see AppNotificationListener.kt.
                BccuConnectionService.sendNotificationIfRunning(
                    "RMT EXIT IN 5 SEC",
                    BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
                )
            }
        }
        stateMachine = ControllerStateMachine(actions)

        callStateMonitor = CallStateMonitor(this) { ringing ->
            callRingingState.value = ringing
            stateMachine.isCallRinging = ringing
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            callStateMonitor.start()
        }

        // Drive the state machine from RCM button events regardless of what's foregrounded.
        lifecycleScope.launch {
            BccuConnectionService.buttonEvents.collect { button ->
                try {
                    AppLogger.log("Controller", "Button=$button screen=${stateMachine.screen} callRinging=${stateMachine.isCallRinging}")
                    stateMachine.onButton(button, System.currentTimeMillis(), NotificationRepository.entries.value.size)
                    AppLogger.log("Controller", "-> screen=${stateMachine.screen} gridSelection=${stateMachine.gridSelection}")
                } catch (e: Exception) {
                    AppLogger.log("Controller", "!! Handling button=$button threw: $e")
                }
            }
        }
        // Inactivity watchdog tick.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                stateMachine.tick(System.currentTimeMillis())
            }
        }
        // On disconnect, drop back to idle and let whatever was showing (e.g. Maps) remain visible.
        lifecycleScope.launch {
            BccuConnectionService.connectionState.collect { state ->
                if (state == BccuConnectionService.ConnectionState.DISCONNECTED) {
                    stateMachine.reset()
                }
            }
        }

        setContent {
            OpenDashApp(settings = settings, stateMachine = stateMachine, actions = actions)
        }

        // DEBUG-only: `adb shell am broadcast -a com.opendash.app.EXPORT_MANEUVERS`
        // renders Google Maps' own labeled maneuver icons to files/maneuver_dataset/
        // to build a training set for an independent classifier. Stripped from release.
        if (com.opendash.app.BuildConfig.DEBUG) {
            maneuverExportReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    lifecycleScope.launch {
                        val msg = com.opendash.app.notifications.MapsIconExporter.exportAll(this@MainActivity)
                        AppLogger.log("Dataset", msg)
                    }
                }
            }
            val filter = android.content.IntentFilter("com.opendash.app.EXPORT_MANEUVERS")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(maneuverExportReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(maneuverExportReceiver, filter)
            }
        }
    }

    private var maneuverExportReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        maneuverExportReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppForegroundState.isForeground = true
    }

    override fun onPause() {
        super.onPause()
        AppForegroundState.isForeground = false
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.READ_PHONE_STATE
        perms += Manifest.permission.ANSWER_PHONE_CALLS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
private fun OpenDashApp(
    settings: AppSettings,
    stateMachine: ControllerStateMachine,
    actions: ControllerStateMachine.Actions
) {
    var route by remember {
        mutableStateOf(
            when {
                !settings.onboardingComplete -> AppRoute.ONBOARDING
                settings.bondedDeviceAddress == null -> AppRoute.PAIRING
                else -> AppRoute.MAIN
            }
        )
    }
    var logsReturnRoute by remember { mutableStateOf(AppRoute.SETTINGS) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reactively observe the controller state instead of polling it - Compose
    // now recomposes only when screen/selection actually changes.
    val uiState by stateMachine.state.collectAsState()

    // Touch back navigation: sub-pages return to their parent instead of
    // minimizing the app (the physical RCM BACK still works independently).
    androidx.activity.compose.BackHandler(
        enabled = route != AppRoute.PAIRING && route != AppRoute.ONBOARDING
    ) {
        when (route) {
            AppRoute.SETTINGS -> route = AppRoute.MAIN
            AppRoute.LOGS -> route = logsReturnRoute
            AppRoute.SYMBOL_TEST -> route = AppRoute.SETTINGS
            AppRoute.TURN_CALIBRATION -> route = AppRoute.SETTINGS
            AppRoute.MAIN -> if (!stateMachine.touchBack()) {
                (context as? ComponentActivity)?.moveTaskToBack(true)
            }
            AppRoute.PAIRING, AppRoute.ONBOARDING -> {}
        }
    }

    // Connect greeting: when the bike authenticates, show a brief "Hi <name>!"
    // banner with the walking-person icon over whatever screen is up.
    val connState by BccuConnectionService.connectionState.collectAsState()
    var showGreeting by remember { mutableStateOf(false) }
    var prevConn by remember { mutableStateOf(connState) }
    LaunchedEffect(connState) {
        if (connState == BccuConnectionService.ConnectionState.AUTHENTICATED &&
            prevConn != BccuConnectionService.ConnectionState.AUTHENTICATED
        ) {
            showGreeting = true
            delay(4000)
            showGreeting = false
        }
        prevConn = connState
    }

    val highContrast = uiState.screen == ControllerScreen.NOTIFICATIONS
    OpenDashTheme(highContrast = highContrast) {
      androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when (route) {
            AppRoute.ONBOARDING -> com.opendash.app.ui.screens.OnboardingScreen(
                settings = settings,
                onComplete = { route = AppRoute.PAIRING }
            )
            AppRoute.PAIRING -> PairingScreen(
                settings = settings,
                onPaired = { route = AppRoute.MAIN },
                onOpenLogs = { logsReturnRoute = AppRoute.PAIRING; route = AppRoute.LOGS }
            )
            AppRoute.MAIN -> {
                // A single on-screen D-pad overlays every MAIN sub-screen so the
                // phone itself becomes a "gamepad": the same Up/Down/Set/Back
                // pipeline as the handlebar remote, usable without the bike.
                com.opendash.app.ui.components.RemoteDpadScaffold(
                    onButton = { button ->
                        stateMachine.onButton(
                            button,
                            System.currentTimeMillis(),
                            NotificationRepository.entries.value.size,
                        )
                    },
                ) {
                    when (uiState.screen) {
                        ControllerScreen.NOTIFICATIONS -> NotificationScreen(
                            settings = settings,
                            scrollIndex = uiState.notificationScrollIndex
                        )
                        ControllerScreen.DIRECTION -> DirectionScreen(onOpenMaps = actions::openMaps)
                        else -> GridMenuScreen(
                            gridSelection = uiState.gridSelection,
                            onSelectGrid = { index ->
                                stateMachine.touchSelectGrid(index, System.currentTimeMillis())
                            },
                            onOpenSettings = { route = AppRoute.SETTINGS }
                        )
                    }
                }
            }
            AppRoute.SETTINGS -> SettingsScreen(
                settings = settings,
                onBack = { route = AppRoute.MAIN },
                onOpenLogs = { logsReturnRoute = AppRoute.SETTINGS; route = AppRoute.LOGS },
                onOpenSymbolTest = { route = AppRoute.SYMBOL_TEST },
                onOpenTurnCalibration = { route = AppRoute.TURN_CALIBRATION },
                onRepair = {
                    // Forget the pairing flag too, not just the address - otherwise
                    // re-pairing the same bike still replies GENERATE_KEYS and the
                    // handshake stalls if the dash has lost its keys. Clearing it
                    // makes the next handshake do a fresh HELLO (dash prompts).
                    settings.bondedDeviceAddress?.let { settings.clearPairedBefore(it) }
                    settings.bondedDeviceAddress = null
                    settings.bondedDeviceName = null
                    BccuConnectionService.stop(context)
                    stateMachine.reset()
                    route = AppRoute.PAIRING
                }
            )
            AppRoute.LOGS -> com.opendash.app.ui.screens.LogsScreen(
                onBack = { route = logsReturnRoute }
            )
            AppRoute.SYMBOL_TEST -> com.opendash.app.ui.screens.SymbolTestScreen(
                onBack = { route = AppRoute.SETTINGS }
            )
            AppRoute.TURN_CALIBRATION -> com.opendash.app.ui.screens.TurnCalibrationScreen(
                onBack = { route = AppRoute.SETTINGS }
            )
        }
        if (showGreeting) {
            ConnectGreeting(name = settings.userName?.trim().orEmpty())
        }
      }
    }
}

/** Brief "Hi <name>!" banner with the walking-person icon, shown on connect. */
@Composable
private fun ConnectGreeting(name: String) {
    val text = if (name.isNotBlank()) "Hi $name!" else "Welcome!"
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(top = 96.dp),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(com.opendash.app.ui.theme.Ktm.Orange)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(com.opendash.app.R.drawable.dash_pedestrian),
                contentDescription = null,
                tint = com.opendash.app.ui.theme.Ktm.Screen,
                modifier = androidx.compose.ui.Modifier.size(34.dp),
            )
            androidx.compose.material3.Text(
                text,
                color = com.opendash.app.ui.theme.Ktm.Screen,
                fontFamily = com.opendash.app.ui.theme.BarlowCondensed,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 26.sp,
                modifier = androidx.compose.ui.Modifier.padding(start = 12.dp),
            )
        }
    }
}
