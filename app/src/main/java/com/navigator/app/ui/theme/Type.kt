@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.navigator.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.navigator.app.R

/**
 * The three type families from the KTM handoff, bundled as real TTFs under
 * res/font so fidelity does not depend on network/Play-Services at runtime.
 *
 * - [BarlowCondensed]: display / headings / labels (hero headlines & road
 *   names use its italic).
 * - [Barlow]: body & secondary copy.
 * - [JetBrainsMono]: numeric / technical / MAC / codes / time / RSSI.
 */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.barlow_condensed_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

// JetBrains Mono ships as a single variable font; select weights via the
// weight axis so 400/500/700 all render from the one bundled file.
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono_variable, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val Typography = Typography(
    // Body defaults to Barlow so any un-styled Material text stays on-brand.
    bodyLarge = Typography().bodyLarge.copy(fontFamily = Barlow),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = Barlow),
    bodySmall = Typography().bodySmall.copy(fontFamily = Barlow),
    labelLarge = Typography().labelLarge.copy(fontFamily = BarlowCondensed),
)
