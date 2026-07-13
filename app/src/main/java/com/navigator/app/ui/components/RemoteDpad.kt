package com.navigator.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ble.BccuProtocol.HandlebarButton
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Ktm

/**
 * On-screen virtual "gamepad": a compact Up/Down/Set/Back cluster overlaid on
 * the app's own UI, feeding the exact same [HandlebarButton] pipeline as the
 * physical handlebar remote. This makes the phone controllable like the bike's
 * remote even when the bike isn't connected (and doubles as a way to test the
 * menu flow). It's collapsible so it never permanently covers content.
 *
 * The 4-button layout mirrors the real remote (which has no Left/Right); a
 * D-pad's natural Left maps to BACK and its centre to SET, matching the
 * remote's Set/Back semantics.
 */
@Composable
fun RemoteDpadScaffold(
    onButton: (HandlebarButton) -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 26.dp, end = 16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            if (expanded) {
                RemoteCluster(
                    onButton = onButton,
                    onCollapse = { expanded = false },
                )
            } else {
                DpadHandle(onClick = { expanded = true })
            }
        }
    }
}

@Composable
private fun DpadHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Orange, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Simple 4-way glyph.
        Text("✛", color = Ktm.Orange, fontSize = 22.sp)
    }
}

@Composable
private fun RemoteCluster(onButton: (HandlebarButton) -> Unit, onCollapse: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(18.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("REMOTE", color = Ktm.Muted2, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Text("✕", color = Ktm.Dim, fontSize = 15.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onCollapse).padding(4.dp))
        }
        DpadButton("▲", "UP") { onButton(HandlebarButton.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DpadButton("BACK", "BACK", wide = true) { onButton(HandlebarButton.BACK) }
            DpadButton("SET", "SET", wide = true, accent = true) { onButton(HandlebarButton.SET) }
        }
        DpadButton("▼", "DOWN") { onButton(HandlebarButton.DOWN) }
    }
}

@Composable
private fun DpadButton(
    label: String,
    contentDesc: String,
    wide: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(if (wide) Modifier.size(width = 58.dp, height = 40.dp) else Modifier.size(58.dp, 34.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) Ktm.Orange else Ktm.SurfaceAlt)
            .border(1.dp, if (accent) Ktm.Orange else Ktm.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (accent) Ktm.Screen else Ktm.White,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontSize = if (label.length > 2) 14.sp else 18.sp,
            letterSpacing = 0.5.sp,
        )
    }
}
