package com.navigator.app.controller

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.AppForegroundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Turns the handlebar remote into a **system-wide gamepad**. This is the only
 * sanctioned Android way for one app to drive navigation inside *other* apps:
 * an AccessibilityService can move accessibility focus, click the focused
 * element, and perform global Back/Home/Recents.
 *
 * Button mapping (only while OpenDash is NOT the foreground app - when it is,
 * MainActivity's own handler drives OpenDash's UI, so the two never fight):
 *  - UP    -> move focus to the previous focusable element (or scroll up)
 *  - DOWN  -> move focus to the next focusable element (or scroll down)
 *  - SET   -> click the focused element
 *  - BACK  -> global Back
 *
 * It never reads screen content for any purpose other than finding the next
 * focusable node to move the selection to.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    companion object {
        /** True while the service is connected/enabled by the user. */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * System security surfaces the remote gamepad must never drive: a
         * spoofed BLE peer must not be able to approve a permission/consent
         * prompt, confirm an app install, or change system settings by
         * injecting focus-move + click while one of these is in front.
         */
        private val PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        AppLogger.log("Gamepad", "Accessibility service connected - handlebar gamepad available")
        collectJob = s.launch {
            BccuConnectionService.buttonEvents.collect { button ->
                try {
                    handleButton(button)
                } catch (e: Exception) {
                    AppLogger.log("Gamepad", "!! handleButton threw: $e")
                }
            }
        }
        // Debug-only: let `adb shell am broadcast -a com.navigator.app.DEBUG_GAMEPAD --es button UP`
        // inject a button so the gamepad can be exercised on an emulator with no bike.
        // Compiled out of release builds.
        if (com.navigator.app.BuildConfig.DEBUG) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val name = i?.getStringExtra("button")?.uppercase() ?: return
                    val b = BccuProtocol.HandlebarButton.entries.firstOrNull { it.name == name } ?: return
                    AppLogger.log("Gamepad", "DEBUG inject button $name")
                    handleButton(b)
                }
            }
            debugReceiver = r
            val filter = android.content.IntentFilter("com.navigator.app.DEBUG_GAMEPAD")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, filter)
            }
        }
    }

    // We track the selection index ourselves over the on-screen actionable
    // nodes, rather than relying on the system's accessibility focus round-trip
    // (findFocus() often returns null between presses on real screens, which
    // would make focus never advance). Reset whenever the window changes.
    private var selectionIndex = -1
    private var lastWindowId = -1
    private var lastPressAtMs = 0L

    private fun handleButton(button: BccuProtocol.HandlebarButton) {
        lastPressAtMs = System.currentTimeMillis()
        // Only act as a system gamepad when the remote is in GAMEPAD mode AND
        // Navigator Gen3 isn't the foreground app (it drives its own UI itself).
        // Leaving gamepad mode must also clear any lingering selection box.
        if (AppSettings(this).remoteMode != AppSettings.MODE_GAMEPAD || AppForegroundState.isForeground) {
            hideHighlight()
            return
        }

        // The button stream ultimately originates from the (app-layer, not
        // cryptographically authenticated) BLE peer, so a spoofed/rogue peer
        // could inject presses. Never let a remote-driven press click through a
        // system security surface - permission dialogs, the package installer,
        // system UI or Settings - where a stray SET/BACK could approve a consent
        // prompt or dismiss a security warning without the rider's intent.
        val frontPackage = rootInActiveWindow?.packageName?.toString()
        if (frontPackage != null && frontPackage in PROTECTED_PACKAGES) {
            AppLogger.log("Gamepad", "Ignoring $button over protected window $frontPackage")
            return
        }

        when (button) {
            BccuProtocol.HandlebarButton.UP -> moveSelection(-1)
            BccuProtocol.HandlebarButton.DOWN -> moveSelection(+1)
            BccuProtocol.HandlebarButton.SET -> clickSelection()
            BccuProtocol.HandlebarButton.BACK -> {
                AppLogger.log("Gamepad", "BACK -> global back")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    /**
     * Move the selection by [delta] over the actionable nodes currently on
     * screen. We keep our own index (visualised via the system accessibility
     * focus box) so it advances reliably. At either end we scroll the nearest
     * list to bring more items into view.
     */
    private fun moveSelection(delta: Int) {
        val root = rootInActiveWindow ?: run { AppLogger.log("Gamepad", "move: no active window"); return }
        resetIfWindowChanged(root)
        val nodes = collectActionable(root)
        if (nodes.isEmpty()) {
            AppLogger.log("Gamepad", "move: no actionable nodes")
            return
        }
        // First press after a window change: seed the selection at the nearest
        // end so something visibly highlights immediately (UP used to compute
        // -2 and appear completely dead until a DOWN was pressed first).
        val target = if (selectionIndex == -1) {
            if (delta > 0) 0 else nodes.size - 1
        } else selectionIndex + delta
        if (target < 0 || target >= nodes.size) {
            // Past an end: scroll to reveal more, keep the selection pinned to the edge.
            val scrollable = firstScrollable(root)
            if (scrollable != null) {
                val act = if (delta < 0) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                val ok = scrollable.performAction(act)
                AppLogger.log("Gamepad", "move: at edge -> scroll ${if (ok) "ok" else "none"}")
                selectionIndex = -1 // re-seed after the list shifts
            }
            return
        }
        selectionIndex = target
        val node = nodes[selectionIndex]
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        // ACTION_ACCESSIBILITY_FOCUS moves the logical focus but draws NOTHING
        // visible unless TalkBack-style services are on - which is exactly the
        // "gamepad doesn't move anything on the screen" report. Scroll the node
        // into view and draw our own highlight box over it.
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        showHighlight(node)
        AppLogger.log("Gamepad", "select ${selectionIndex + 1}/${nodes.size} ${describe(node)}")
    }

    // --- Visible selection highlight (accessibility overlay) ---

    private var highlightView: android.view.View? = null

    /** Draw (or move) an orange rounded-rect outline over [node]'s screen bounds. */
    private fun showHighlight(node: AccessibilityNodeInfo) {
        try {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val view = highlightView ?: android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((4 * resources.displayMetrics.density).toInt(), 0xFFFF6600.toInt())
                    cornerRadius = 12 * resources.displayMetrics.density
                    setColor(0x22FF6600)
                }
            }.also { highlightView = it }
            val lp = android.view.WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    // getBoundsInScreen() is in absolute screen coordinates; without
                    // this the window is positioned relative to the area below the
                    // status bar, drawing the box ~a status-bar too low everywhere.
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = bounds.left
                y = bounds.top
                if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            }
            if (view.parent == null) wm.addView(view, lp) else wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            AppLogger.log("Gamepad", "!! highlight failed: $e")
        }
    }

    private fun hideHighlight() {
        val view = highlightView ?: return
        highlightView = null
        runCatching {
            if (view.parent != null) (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view)
        }
    }

    /** Click the currently-selected node (walking up to a clickable ancestor). */
    private fun clickSelection() {
        val root = rootInActiveWindow ?: return
        resetIfWindowChanged(root)
        val nodes = collectActionable(root)
        if (nodes.isEmpty()) { AppLogger.log("Gamepad", "SET: nothing to click"); return }
        if (selectionIndex !in nodes.indices) selectionIndex = 0
        var target: AccessibilityNodeInfo? = nodes[selectionIndex]
        while (target != null && !target.isClickable) target = target.parent
        val hit = target ?: nodes[selectionIndex]
        val ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLogger.log("Gamepad", "SET -> click ${describe(hit)} ${if (ok) "" else "(failed)"}")
    }

    private fun resetIfWindowChanged(root: AccessibilityNodeInfo) {
        val wid = root.windowId
        if (wid != lastWindowId) {
            lastWindowId = wid
            selectionIndex = -1
            hideHighlight()
        }
    }

    /**
     * Flatten the node tree to a reading-ordered list of things worth selecting:
     * on-screen, enabled, and either clickable/checkable or a focusable node that
     * carries a label. Nested actionables keep the INNERMOST nodes: selecting the
     * actual small buttons, not the whole-screen container around them (the R20
     * field test hit full-page "Home screen 1"-sized selections that made SET
     * unpredictable). A container with no actionable descendants is itself the
     * leaf, so plain list rows still select as a row.
     */
    private fun collectActionable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val minSidePx = (12 * resources.displayMetrics.density).toInt()
        val bounds = android.graphics.Rect()
        // Returns true when this subtree contributed at least one selectable.
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            var childTook = false
            for (i in 0 until node.childCount) {
                if (visit(node.getChild(i))) childTook = true
            }
            if (childTook) return true // descendants are the finer-grained targets
            val onScreen = node.isVisibleToUser && node.isEnabled
            if (!onScreen) return false
            val labelled = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val actionable = node.isClickable || node.isCheckable || (node.isFocusable && labelled)
            if (!actionable) return false
            // Skip sub-finger-size targets (decorative 1px views showed up as
            // unlabelled [View] selections that appeared to do nothing).
            node.getBoundsInScreen(bounds)
            if (bounds.width() < minSidePx || bounds.height() < minSidePx) return false
            out.add(node)
            return true
        }
        visit(root)
        return out
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            firstScrollable(c)?.let { return it }
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.take(24)
        val desc = node.contentDescription?.toString()?.take(24)
        val cls = node.className?.toString()?.substringAfterLast('.')
        return "[$cls${if (!text.isNullOrBlank()) " '$text'" else if (!desc.isNullOrBlank()) " '$desc'" else ""}]"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Remove the selection box only when the screen GENUINELY changed
        // underneath it (another app came up, SET opened a new screen). The
        // R20 field test showed it vanishing instantly after every press:
        // WINDOW_STATE_CHANGED also fires for our own overlay being added and
        // for cosmetic same-window events (Maps re-posts constantly), and the
        // old unconditional hide + index reset made the selector unusable.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || highlightView == null) return
        if (event.packageName == packageName) return // our own highlight/mode-picker windows
        if (System.currentTimeMillis() - lastPressAtMs < 1200) return // change WE caused (click/scroll)
        val activeWid = rootInActiveWindow?.windowId ?: return
        if (activeWid == lastWindowId) return // same window, just state noise
        hideHighlight()
        selectionIndex = -1
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        isRunning = false
        hideHighlight()
        collectJob?.cancel()
        scope?.cancel()
        scope = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        AppLogger.log("Gamepad", "Accessibility service destroyed")
        super.onDestroy()
    }
}
