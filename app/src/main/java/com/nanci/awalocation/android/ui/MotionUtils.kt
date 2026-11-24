package com.nanci.awalocation.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.google.android.material.card.MaterialCardView
import com.nanci.awalocation.R

object MotionUtils {
    val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    val STANDARD_DECELERATE = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
}

fun View.animateSlideFadeIn(
    offsetYDp: Float = 16f,
    durationMs: Long = 240L,
    startScale: Float = 0.98f
) {
    val offsetPx = offsetYDp * resources.displayMetrics.density
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        translationY = offsetPx
        scaleX = startScale
        scaleY = startScale
        visibility = View.VISIBLE
    }
    animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(durationMs)
        .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
        .withEndAction(null)
        .start()
}

fun View.animateSlideFadeOut(
    offsetYDp: Float = 12f,
    durationMs: Long = 160L,
    endVisibility: Int = View.GONE,
    onEnd: (() -> Unit)? = null
) {
    if (visibility != View.VISIBLE) {
        visibility = endVisibility
        onEnd?.invoke()
        return
    }
    val offsetPx = offsetYDp * resources.displayMetrics.density
    animate().cancel()
    animate()
        .alpha(0f)
        .translationY(offsetPx)
        .setDuration(durationMs)
        .setInterpolator(MotionUtils.EMPHASIZED_ACCELERATE)
        .withEndAction {
            visibility = endVisibility
            alpha = 1f
            translationY = 0f
            onEnd?.invoke()
        }
        .start()
}

fun View.animateScalePop(peakScale: Float = 1.045f, durationMs: Long = 220L) {
    animate().cancel()
    val half = durationMs / 2L
    animate()
        .scaleX(peakScale)
        .scaleY(peakScale)
        .setDuration(half)
        .setInterpolator(MotionUtils.STANDARD_DECELERATE)
        .withEndAction {
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(half)
                .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
                .start()
        }
        .start()
}

fun View.startLivePulse() {
    val existing = getTag(R.id.tag_pulse_animator) as? AnimatorSet
    if (existing != null && existing.isRunning) return
    existing?.cancel()

    val alphaAnim = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.35f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
    }
    val scaleXAnim = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 1.28f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
    }
    val scaleYAnim = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, 1.28f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
    }
    val set = AnimatorSet().apply {
        interpolator = MotionUtils.STANDARD_DECELERATE
        playTogether(alphaAnim, scaleXAnim, scaleYAnim)
        start()
    }
    setTag(R.id.tag_pulse_animator, set)
}

fun View.stopLivePulse() {
    (getTag(R.id.tag_pulse_animator) as? AnimatorSet)?.cancel()
    setTag(R.id.tag_pulse_animator, null)
    alpha = 1f
    scaleX = 1f
    scaleY = 1f
}

fun MaterialCardView.animateStrokeColor(targetColor: Int, targetWidthPx: Int, durationMs: Long = 220L) {
    val currentColor = strokeColorStateList?.defaultColor ?: targetColor
    strokeWidth = targetWidthPx
    if (currentColor == targetColor || !isAttachedToWindow) {
        strokeColor = targetColor
        return
    }
    (getTag(R.id.tag_stroke_animator) as? ValueAnimator)?.cancel()
    val animator = ValueAnimator.ofArgb(currentColor, targetColor).apply {
        duration = durationMs
        interpolator = MotionUtils.STANDARD_DECELERATE
        addUpdateListener { strokeColor = it.animatedValue as Int }
        start()
    }
    setTag(R.id.tag_stroke_animator, animator)
}

fun ViewGroup.animateChildrenEntrance(maxCount: Int = 6, staggerMs: Long = 35L) {
    val offsetPx = 14f * resources.displayMetrics.density
    var animatedIndex = 0
    for (i in 0 until childCount) {
        if (animatedIndex >= maxCount) break
        val child = getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        child.animate().cancel()
        child.alpha = 0f
        child.translationY = offsetPx
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(animatedIndex * staggerMs)
            .setDuration(240L)
            .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
            .start()
        animatedIndex++
    }
}
