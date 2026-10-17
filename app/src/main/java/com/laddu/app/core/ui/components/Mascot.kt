package com.laddu.app.core.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlin.math.PI
import kotlin.math.sin

/** The mascot is a companion character, not the user's dog. Its mood never states anything about the real dog's safety. */
enum class MascotMood { HAPPY, CURIOUS, SLEEPING, CONCERNED }

/** True when the system "remove animations" setting is on (animator scale 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

private val Fur = Color(0xFFE7B07A)
private val FurDark = Color(0xFFB77C4A)
private val Muzzle = Color(0xFFFFF1DE)
private val Ink = Color(0xFF2B1E16)

/**
 * Drawn with Canvas (no assets, no per-frame allocation beyond the draw calls). Idle motion is one occasional blink and a short
 * tail wag every ~5 s, and it stops when the screen is not resumed or the system has animations turned off.
 * [contentDescription] = null marks it decorative so TalkBack skips it.
 */
@Composable
fun LadduMascot(
    mood: MascotMood,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    contentDescription: String? = null,
) {
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val moving = animate && !rememberReducedMotion() && resumed.isAtLeast(Lifecycle.State.RESUMED) && mood != MascotMood.SLEEPING
    val phase: State<Float> =
        if (moving) rememberInfiniteTransition(label = "mascot").animateFloat(
            0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        ) else remember { mutableStateOf(0f) }

    val sem = if (contentDescription == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.size(size).then(sem)) {
        // read inside the draw phase: animating redraws only, never recomposes
        val t = phase.value
        val blink = t in 0.90f..0.94f
        val wag = if (t < 0.25f) sin(t / 0.25f * PI.toFloat() * 4f) * 22f else 0f
        drawPuppy(mood, blink, wag)
    }
}

private fun DrawScope.drawPuppy(mood: MascotMood, blink: Boolean, tailDeg: Float) {
    val w = size.width
    fun p(x: Float, y: Float) = Offset(w * x, w * y)

    // tail (behind the head, pivot at the lower right)
    rotate(tailDeg, pivot = p(0.80f, 0.80f)) {
        drawRoundRect(FurDark, p(0.78f, 0.60f), Size(w * 0.12f, w * 0.26f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
    }
    // ears
    val earDrop = if (mood == MascotMood.CONCERNED) 0.04f else if (mood == MascotMood.CURIOUS) -0.03f else 0f
    drawRoundRect(FurDark, p(0.14f, 0.12f + earDrop), Size(w * 0.20f, w * 0.34f), androidx.compose.ui.geometry.CornerRadius(w * 0.10f))
    drawRoundRect(FurDark, p(0.66f, 0.12f), Size(w * 0.20f, w * 0.34f), androidx.compose.ui.geometry.CornerRadius(w * 0.10f))
    // head
    drawCircle(Fur, w * 0.34f, p(0.50f, 0.54f))
    // muzzle + nose
    drawCircle(Muzzle, w * 0.17f, p(0.50f, 0.66f))
    drawCircle(Ink, w * 0.045f, p(0.50f, 0.60f))

    // eyes
    val eyeY = 0.48f
    val closed = blink || mood == MascotMood.SLEEPING
    for (x in listOf(0.38f, 0.62f)) {
        if (closed) {
            drawArc(Ink, 0f, 180f, false, p(x - 0.045f, eyeY - 0.02f), Size(w * 0.09f, w * 0.05f), style = Stroke(w * 0.022f, cap = StrokeCap.Round))
        } else {
            drawCircle(Ink, w * 0.04f, p(x, eyeY))
            drawCircle(Color.White, w * 0.013f, p(x + 0.012f, eyeY - 0.014f))
        }
    }
    // mouth / brows
    when (mood) {
        MascotMood.HAPPY -> drawArc(Ink, 0f, 180f, false, p(0.43f, 0.66f), Size(w * 0.14f, w * 0.10f), style = Stroke(w * 0.022f, cap = StrokeCap.Round))
        MascotMood.CONCERNED -> {
            drawLine(Ink, p(0.32f, 0.39f), p(0.43f, 0.42f), w * 0.022f, StrokeCap.Round)
            drawLine(Ink, p(0.68f, 0.39f), p(0.57f, 0.42f), w * 0.022f, StrokeCap.Round)
            drawLine(Ink, p(0.46f, 0.74f), p(0.54f, 0.74f), w * 0.02f, StrokeCap.Round)
        }
        MascotMood.CURIOUS -> drawCircle(Ink, w * 0.02f, p(0.50f, 0.74f))
        MascotMood.SLEEPING -> drawLine(Ink, p(0.46f, 0.73f), p(0.54f, 0.73f), w * 0.02f, StrokeCap.Round)
    }
}
