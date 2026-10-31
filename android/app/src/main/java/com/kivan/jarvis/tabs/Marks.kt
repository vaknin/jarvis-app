package com.kivan.jarvis.tabs

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R

/** Touch answers are crisp and always go with something on screen. No permission is needed. */
class Buzz(private val view: View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun step() = view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    fun lift() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    fun sent() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun refused() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun toggle(on: Boolean) = view.performHapticFeedback(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
}

@Composable
fun rememberBuzz(): Buzz {
    val view = LocalView.current
    return remember(view) { Buzz(view) }
}

/**
 * The one moving thing, shared by every line on screen, so they glide as one and cost one clock. It
 * stops when nothing moving is showing: a hidden window draws nothing.
 */
val LocalGlide = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0.3f) }

@Composable
fun rememberGlide(running: Boolean): State<Float> {
    val glide = remember { mutableFloatStateOf(0.3f) }
    val still = LocalStill.current
    LaunchedEffect(running, still) {
        if (!running || still) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t -> glide.floatValue = ((t - start) / 1_900_000_000f) % 1f }
    }
    return glide
}

/** Working: one segment gliding at a constant speed over [width] of nothing. Drawn, never composed. */
fun Modifier.workLine(color: Color, glide: State<Float>, thickness: Dp = 2.dp, segment: Float = 0.3f): Modifier =
    drawBehind {
        val t = thickness.toPx()
        val seg = size.width * segment
        val x = (size.width + seg) * glide.value - seg
        val left = x.coerceAtLeast(0f)
        val right = (x + seg).coerceAtMost(size.width)
        if (right > left) {
            drawRoundRect(
                color,
                Offset(left, (size.height - t) / 2),
                Size(right - left, t),
                CornerRadius(t / 2),
            )
        }
    }

/** Needs you: a filled triangle, the only filled shape on the wall. */
@Composable
fun Triangle(color: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.1f
        val p = Path().apply {
            moveTo(w / 2, h * 0.1f)
            lineTo(w - r, h * 0.9f)
            lineTo(r, h * 0.9f)
            close()
        }
        drawPath(p, color)
        drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 1.4f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** New to read. */
@Composable
fun Dot(color: Color = look.new, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
fun Glyph(@DrawableRes icon: Int, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier, label: String? = null) {
    Icon(painterResource(icon), label, modifier.size(size), tint)
}

/** A round button: [size] drawn, never under 48 dp to the finger. */
@Composable
fun Round(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    tint: Color = look.soft,
    size: Dp = 44.dp,
) {
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) 0.88f else 1f, Curves.press)
    Box(
        modifier
            .size(Space.target)
            .clickable(press, null, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(fill, CircleShape),
            Alignment.Center,
        ) { Glyph(icon, tint, 22.dp) }
    }
}

/**
 * Mute, in the same corner of every screen, under the thumb. The mic is on until it's pressed, so
 * listening is the quiet state and muted is the one that stands out: the button fills and squares
 * off, so it reads without colour and without a word.
 */
@Composable
fun MuteButton(muted: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val buzz = rememberBuzz()
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) 0.88f else 1f, Curves.press)
    val fill by animateColorAsState(if (muted) look.ink else look.surface, tween(Curves.QUICK))
    val tint by animateColorAsState(if (muted) look.canvas else look.soft, tween(Curves.QUICK))
    val corner by animateDpAsState(if (muted) 14.dp else 24.dp, tween(Curves.PAGE, easing = Curves.out))
    Box(
        modifier
            .size(Space.target)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .toggleable(muted, press, ripple(color = tint), role = Role.Switch) {
                buzz.toggle(it)
                onChange(it)
            }
            .semantics {
                contentDescription = "Mute"
                stateDescription = if (muted) "Muted, nothing is heard" else "Listening"
            },
        Alignment.Center,
    ) {
        Glyph(if (muted) R.drawable.lucide_mic_off else R.drawable.lucide_mic, tint, 22.dp)
    }
}
