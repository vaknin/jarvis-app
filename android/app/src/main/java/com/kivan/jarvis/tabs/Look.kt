package com.kivan.jarvis.tabs

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.jarvis.R

/**
 * The look of the tabs screens: design/jarvis-app/LOOK.md in the jarvis repo, its roles by name. Only
 * this file holds a colour, a size of type or a curve; a screen names a role. Dynamic colour stays off,
 * since the marks mean one thing each.
 */
@Immutable
data class Look(
    val dark: Boolean,
    val canvas: Color,
    val ink: Color,
    val soft: Color,
    val faint: Color,
    val line: Color,
    val surface: Color,
    val raised: Color,
    val urgent: Color,
    val urgentText: Color,
    val onUrgent: Color,
    val new: Color,
)

private val Dark = Look(
    dark = true,
    canvas = Color(0xFF0F1115),
    ink = Color(0xFFE8EAED),
    soft = Color(0xFFB0B2B5),
    faint = Color(0xFF96989C),
    line = Color(0xFF3A3C40),
    surface = Color(0xFF1E2024),
    raised = Color(0xFF2B2D31),
    urgent = Color(0xFFF2B33D),
    urgentText = Color(0xFFF2B33D),
    onUrgent = Color(0xFF241A00),
    new = Color(0xFF6DB3F8),
)

private val Light = Look(
    dark = false,
    canvas = Color(0xFFFBFBFA),
    ink = Color(0xFF1B1D20),
    soft = Color(0xFF48494C),
    faint = Color(0xFF626364),
    line = Color(0xFFCECFCE),
    surface = Color(0xFFEBEBE9),
    raised = Color(0xFFE0E0DE),
    urgent = Color(0xFFF6C453),
    urgentText = Color(0xFF8F5A00),
    onUrgent = Color(0xFF241A00),
    new = Color(0xFF0A63CE),
)

val LocalLook = staticCompositionLocalOf { Dark }

/** True when the system's animations are off: a moving line becomes words, a zoom becomes a cut. */
val LocalStill = staticCompositionLocalOf { false }

val look: Look @Composable get() = LocalLook.current

private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Inter = FontFamily(inter(400), inter(500), inter(600))

private val Mono = FontFamily(
    Font(R.font.geist_mono, weight = FontWeight(400), variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist_mono, weight = FontWeight(500), variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val Base = TextStyle(
    fontFamily = Inter,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Sizes are sp, so they follow the system's font scale; layouts leave room for 200%. */
object Words {
    /** Jarvis's words: what most of the app is. A long line height, since it's read, not skimmed. */
    val prose = Base.copy(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = (-0.08).sp)
    val proseHead = Base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.2).sp)

    /** Aviv's words, in the panel, and what he's typing. */
    val yours = Base.copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-0.08).sp)
    val title = Base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.2).sp)
    val label = Base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W500)
    val small = Base.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val number = Base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.W500, fontFeatureSettings = "tnum")
    val code = Base.copy(fontFamily = Mono, fontSize = 13.sp, lineHeight = 20.sp)

    /** A tile's name: large while there's room for it, the type is the wall's one decoration. */
    fun tile(size: TileSize) = when (size) {
        TileSize.Big -> Base.copy(fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.W500, letterSpacing = (-0.7).sp)
        TileSize.Large -> Base.copy(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.W500, letterSpacing = (-0.4).sp)
        TileSize.Medium -> Base.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.W500, letterSpacing = (-0.25).sp)
        TileSize.Small -> Base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.W500, letterSpacing = (-0.1).sp)
    }
}

object Space {
    val margin = 20.dp
    val para = 14.dp

    /** Between one speaker and the next: wider than any line height, so a turn reads as a turn. */
    val turn = 28.dp
    val gap = 8.dp
    val target = 48.dp

    /** A page of words stops widening here, about the terminal's 92 columns. */
    val reading = 640.dp
}

object Curves {
    /** Presses: quick and a little alive. */
    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 800f)

    /** The zoom between the wall and a tab, a touch under 300 ms, no bounce on arrival. */
    val zoom = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

    /** Tiles making room. */
    val settle = Spring.StiffnessMediumLow
    val out = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val QUICK = 150
    const val PAGE = 280
}

@Composable
fun TabsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val roles = if (dark) Dark else Light
    val context = LocalContext.current
    val still = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // Menus, ripples and handles come from Material: they get the same roles.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = roles.ink,
        onPrimary = roles.canvas,
        background = roles.canvas,
        onBackground = roles.ink,
        surface = roles.canvas,
        onSurface = roles.ink,
        onSurfaceVariant = roles.soft,
        surfaceContainer = roles.raised,
        surfaceContainerLow = roles.surface,
        surfaceContainerHigh = roles.raised,
        outline = roles.line,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalLook provides roles,
            LocalStill provides still,
            LocalTextSelectionColors provides TextSelectionColors(roles.ink, roles.ink.copy(alpha = 0.28f)),
            content = content,
        )
    }
}
