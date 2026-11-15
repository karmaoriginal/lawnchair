/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.wallpaper.animated

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Quality presets for animated wallpapers.
 *
 * A preset trades decode resolution and the maximum number of surface repaints per second against
 * battery usage. [minBatteryPercent] is only used when the user allows low-battery pausing; a
 * charging device ignores it.
 */
enum class AnimatedWallpaperQuality(
    val resolutionScale: Float,
    val maxPixels: Long,
    val maxFrameRate: Int,
    val minBatteryPercent: Int,
) {
    HIGH(
        resolutionScale = 1f,
        maxPixels = 8_294_400L, // 3840 x 2160
        maxFrameRate = 30,
        minBatteryPercent = 25,
    ),
    BALANCED(
        resolutionScale = 0.85f,
        maxPixels = 4_147_200L, // 1920 x 1080, doubled
        maxFrameRate = 24,
        minBatteryPercent = 15,
    ),
    BATTERY_SAVER(
        resolutionScale = 0.65f,
        maxPixels = 2_073_600L, // 1920 x 1080
        maxFrameRate = 15,
        minBatteryPercent = 0,
    ),
    ;

    companion object {
        fun fromPreference(value: String): AnimatedWallpaperQuality = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

/** Inputs used to decide whether an animated wallpaper may keep decoding frames. */
data class AnimatedWallpaperState(
    val enabled: Boolean,
    val visible: Boolean,
    val screenOn: Boolean,
    val powerSaveMode: Boolean,
    val sourceAnimated: Boolean,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val pauseOnPowerSave: Boolean = true,
    val pauseOnLowBattery: Boolean = true,
)

enum class AnimatedWallpaperPauseReason {
    NONE,
    DISABLED,
    NOT_VISIBLE,
    SCREEN_OFF,
    POWER_SAVER,
    LOW_BATTERY,
    STATIC_SOURCE,
}

data class AnimatedWallpaperPlayback(
    val animate: Boolean,
    val frameIntervalMs: Long,
    val reason: AnimatedWallpaperPauseReason,
)

/** An integer size, kept free of Android types so the policy can run in JVM unit tests. */
data class AnimatedWallpaperSize(val width: Int, val height: Int)

/** An integer rectangle, kept free of Android types so the policy can run in JVM unit tests. */
data class AnimatedWallpaperRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

object AnimatedWallpaperPolicy {
    private const val MAX_SAMPLE_SIZE = 16
    private const val LOW_BATTERY_ADAPTIVE_PERCENT = 40

    /**
     * Decides whether the engine should animate or show a frozen frame.
     *
     * The engine never runs a polling loop while paused: [AnimatedWallpaperPauseReason] is used to
     * stop the platform's RenderThread animation and draw one static bitmap instead.
     */
    fun resolvePlayback(
        state: AnimatedWallpaperState,
        quality: AnimatedWallpaperQuality,
    ): AnimatedWallpaperPlayback {
        val paused = when {
            !state.enabled -> AnimatedWallpaperPauseReason.DISABLED

            !state.visible -> AnimatedWallpaperPauseReason.NOT_VISIBLE

            !state.screenOn -> AnimatedWallpaperPauseReason.SCREEN_OFF

            state.pauseOnPowerSave && state.powerSaveMode -> AnimatedWallpaperPauseReason.POWER_SAVER

            state.pauseOnLowBattery && isBatteryTooLow(state, quality) ->
                AnimatedWallpaperPauseReason.LOW_BATTERY

            !state.sourceAnimated -> AnimatedWallpaperPauseReason.STATIC_SOURCE

            else -> null
        }
        if (paused != null) {
            return AnimatedWallpaperPlayback(
                animate = false,
                frameIntervalMs = Long.MAX_VALUE,
                reason = paused,
            )
        }

        // On a discharging battery below 40%, halve the repaint cadence even before the hard
        // low-battery threshold is reached. Decoding still happens on the platform RenderThread,
        // but the wallpaper surface is repainted less often.
        val adaptiveSlowdown = state.batteryPercent != null &&
            !state.charging &&
            state.batteryPercent < LOW_BATTERY_ADAPTIVE_PERCENT
        val frameIntervalMs = (1_000L / quality.maxFrameRate.toLong())
            .coerceAtLeast(1L)
            .let { if (adaptiveSlowdown) it * 2L else it }
        return AnimatedWallpaperPlayback(
            animate = true,
            frameIntervalMs = frameIntervalMs,
            reason = AnimatedWallpaperPauseReason.NONE,
        )
    }

    private fun isBatteryTooLow(
        state: AnimatedWallpaperState,
        quality: AnimatedWallpaperQuality,
    ): Boolean {
        val percent = state.batteryPercent ?: return false
        return !state.charging && percent in 0..quality.minBatteryPercent
    }

    /** True when enough time has passed to repaint the wallpaper at the requested cadence. */
    fun shouldRenderFrame(
        lastRenderTimeMs: Long,
        nowMs: Long,
        frameIntervalMs: Long,
    ): Boolean {
        if (frameIntervalMs <= 0L) return true
        // uptimeMillis is monotonic, but a clock reset should not freeze the wallpaper forever.
        if (nowMs < lastRenderTimeMs) return true
        return nowMs - lastRenderTimeMs >= frameIntervalMs
    }

    /**
     * Bounds the bitmap used for the frozen frame and for memory budgeting. The larger side is
     * scaled by [AnimatedWallpaperQuality.resolutionScale] and the result is capped by
     * [AnimatedWallpaperQuality.maxPixels].
     */
    fun resolveRenderSize(
        surfaceWidth: Int,
        surfaceHeight: Int,
        quality: AnimatedWallpaperQuality,
    ): AnimatedWallpaperSize {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return AnimatedWallpaperSize(1, 1)
        val width = (surfaceWidth * quality.resolutionScale).toInt().coerceAtLeast(1)
        val height = (surfaceHeight * quality.resolutionScale).toInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels <= quality.maxPixels) return AnimatedWallpaperSize(width, height)
        val factor = sqrt(quality.maxPixels.toDouble() / pixels.toDouble())
        return AnimatedWallpaperSize(
            width = (width * factor).toInt().coerceAtLeast(1),
            height = (height * factor).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Returns a power-of-two `ImageDecoder.setTargetSampleSize` value for the source.
     *
     * Animated wallpapers are drawn with center-crop, so a portrait screen showing a landscape
     * source must not be downsampled based on surface area alone. This keeps enough pixels to cover
     * the longer side while still honoring the preset's memory budget.
     */
    fun decodeSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        quality: AnimatedWallpaperQuality,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return 1

        // Largest sample that still covers the surface without more upscaling than the preset
        // allows. Doubling the sample doubles the scale needed to fill the surface.
        val baseCoverScale = max(
            surfaceWidth.toDouble() / sourceWidth,
            surfaceHeight.toDouble() / sourceHeight,
        )
        val maxUpscale = 1.0 / quality.resolutionScale.toDouble()
        var qualitySample = 1
        while (qualitySample < MAX_SAMPLE_SIZE) {
            val next = qualitySample * 2
            if (baseCoverScale * next > maxUpscale) break
            qualitySample = next
        }

        // Largest sample needed to stay inside the memory budget.
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        var memorySample = 1
        while (
            memorySample < MAX_SAMPLE_SIZE &&
            sourcePixels / (memorySample.toLong() * memorySample) > quality.maxPixels
        ) {
            memorySample *= 2
        }
        return max(qualitySample, memorySample)
    }

    /** Center-crop bounds that preserve the source aspect ratio while filling the surface. */
    fun coverBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): AnimatedWallpaperRect {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return AnimatedWallpaperRect(0, 0, viewWidth.coerceAtLeast(0), viewHeight.coerceAtLeast(0))
        }
        val scale = max(
            viewWidth.toDouble() / sourceWidth,
            viewHeight.toDouble() / sourceHeight,
        )
        val scaledWidth = ceil(sourceWidth * scale).toInt()
        val scaledHeight = ceil(sourceHeight * scale).toInt()
        val left = (viewWidth - scaledWidth) / 2
        val top = (viewHeight - scaledHeight) / 2
        return AnimatedWallpaperRect(
            left = left,
            top = top,
            right = left + scaledWidth,
            bottom = top + scaledHeight,
        )
    }
}
