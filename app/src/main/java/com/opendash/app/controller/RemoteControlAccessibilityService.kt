package com.opendash.app.controller

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.opendash.app.ble.BccuConnectionService
import com.opendash.app.ble.BccuProtocol
import com.opendash.app.logging.AppLogger
import com.opendash.app.settings.AppSettings
import com.opendash.app.ui.AppForegroundState
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
        // Debug-only: let `adb shell am broadcast -a com.opendash.app.DEBUG_GAMEPAD --es button UP`
        // inject a button so the gamepad can be exercised on an emulator with no bike.
        // Compiled out of release builds.
        if (com.opendash.app.BuildConfig.DEBUG) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val name = i?.getStringExtra("button")?.uppercase() ?: return
                    val b = BccuProtocol.HandlebarButton.entries.firstOrNull { it.name == name } ?: return
                    AppLogger.log("Gamepad", "DEBUG inject button $name")
                    handleButton(b)
                }
            }
            debugReceiver = r
            val filter = android.content.IntentFilter("com.opendash.app.DEBUG_GAMEPAD")
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

    private fun handleButton(button: BccuProtocol.HandlebarButton) {
        // Only act as a system gamepad when enabled AND OpenDash isn't the app in
        // front (OpenDash drives its own UI itself). This is the clean hand-off.
        if (!AppSettings(this).gamepadEnabled) return
        if (AppForegroundState.isForeground) return

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
        val target = selectionIndex + delta
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
        AppLogger.log("Gamepad", "select ${selectionIndex + 1}/${nodes.size} ${describe(node)}")
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
        }
    }

    /**
     * Flatten the node tree to a reading-ordered list of things worth selecting:
     * on-screen, enabled, and either clickable/checkable or a focusable node that
     * carries a label. De-duplicates nested clickables (keeps the outermost).
     */
    private fun collectActionable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, clickableAncestor: Boolean) {
            if (node == null) return
            val onScreen = node.isVisibleToUser && node.isEnabled
            val labelled = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val actionable = node.isClickable || node.isCheckable || (node.isFocusable && labelled)
            // Keep the outermost actionable container; don't also add its inner
            // clickable children (avoids selecting the same row twice).
            val take = actionable && !clickableAncestor && onScreen
            if (take) out.add(node)
            val childAncestor = clickableAncestor || node.isClickable
            for (i in 0 until node.childCount) visit(node.getChild(i), childAncestor)
        }
        visit(root, false)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* navigation is button-driven, nothing to do here */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        isRunning = false
        collectJob?.cancel()
        scope?.cancel()
        scope = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        AppLogger.log("Gamepad", "Accessibility service destroyed")
        super.onDestroy()
    }
}
