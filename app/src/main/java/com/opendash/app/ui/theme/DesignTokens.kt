package com.opendash.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Exact design tokens from the KTM-inspired "OpenDash KTM" handoff
 * (new ui/design_handoff_opendash_ui). Every hex here is reproduced 1:1
 * from the reference so screens can match the spec closely. Material3's
 * colorScheme only carries a handful of slots, so all the bespoke surface/
 * border/text shades live here as plain [Color]s referenced directly by the
 * redesigned screens.
 */
object Ktm {
    // Brand
    val Orange = Color(0xFFFF6600)
    val OrangeDeep = Color(0xFFE05500)

    // Backgrounds
    val Board = Color(0xFF08090A)
    val Screen = Color(0xFF0B0C0E)
    val Black = Color(0xFF000000)
    val ScreenDeep = Color(0xFF050505)

    // Surfaces / cards
    val Surface = Color(0xFF141618)
    val SurfaceAlt = Color(0xFF101214)
    val SurfaceAlt2 = Color(0xFF101215)
    val NotifCard = Color(0xFF0F0F10)
    val SurfaceDisabled = Color(0xFF1A1C1F)
    val ConnBanner = Color(0xFF0F1416)
    val MonoChip = Color(0xFF191B1F)
    val DashBanner = Color(0xFF0C0C0C)

    // Borders
    val Border = Color(0xFF24272D)
    val Bezel = Color(0xFF2A2D33)
    val RowDivider = Color(0xFF202329)
    val NotifBorder = Color(0xFF1E1E20)
    val ConnBorder = Color(0xFF1D3A2B)
    val MonoChipBorder = Color(0xFF26282E)
    val BorderSoft = Color(0xFF3A3D44)

    // Text
    val White = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFFE6E7EA)
    val TextSecondary = Color(0xFFC9CDD3)
    val Muted = Color(0xFF9AA0A8)
    val Muted2 = Color(0xFF8A8F98)
    val Dim = Color(0xFF6C727B)
    val Dim2 = Color(0xFF5C626B)
    val Dim3 = Color(0xFF4C525B)
    val ConnSub = Color(0xFF7B8188)

    // Semantic
    val Green = Color(0xFF34D07F)
    val Danger = Color(0xFFFF4438)

    // Common radii
    val RadiusCard = 16.dp
    val RadiusButton = 12.dp
    val RadiusRow = 13.dp
}
