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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWallpaperPolicyTest {
    private fun animatingState() = AnimatedWallpaperState(
        enabled = true,
        visible = true,
        screenOn = true,
        powerSaveMode = false,
        sourceAnimated = true,
        batteryPercent = 80,
        charging = false,
    )

    @Test
    fun playbackAnimatesWhenVisibleAndHealthy() {
        val playback = AnimatedWallpaperPolicy.resolvePlayback(
            state = animatingState(),
            quality = AnimatedWallpaperQuality.HIGH,
        )

        assertTrue(playback.animate)
        assertEquals(AnimatedWallpaperPauseReason.NONE, playback.reason)
        assertEquals(33L, playback.frameIntervalMs)
    }

    @Test
    fun playbackPausesInsteadOfPolling() {
        val pausedStates = listOf(
            animatingState().copy(enabled = false) to AnimatedWallpaperPauseReason.DISABLED,
            animatingState().copy(visible = false) to AnimatedWallpaperPauseReason.NOT_VISIBLE,
            animatingState().copy(screenOn = false) to AnimatedWallpaperPauseReason.SCREEN_OFF,
            animatingState().copy(powerSaveMode = true) to AnimatedWallpaperPauseReason.POWER_SAVER,
            animatingState().copy(batteryPercent = 20) to AnimatedWallpaperPauseReason.LOW_BATTERY,
            animatingState().copy(sourceAnimated = false) to AnimatedWallpaperPauseReason.STATIC_SOURCE,
        )

        pausedStates.forEach { (state, reason) ->
            val playback = AnimatedWallpaperPolicy.resolvePlayback(
                state = state,
                quality = AnimatedWallpaperQuality.HIGH,
            )
            assertFalse("Expected a frozen frame for $reason", playback.animate)
            assertEquals(reason, playback.reason)
            assertEquals(Long.MAX_VALUE, playback.frameIntervalMs)
        }
    }

    @Test
    fun chargingAndUserOverridesAllowAnimation() {
        val charging = AnimatedWallpaperPolicy.resolvePlayback(
            state = animatingState().copy(batteryPercent = 5, charging = true),
            quality = AnimatedWallpaperQuality.HIGH,
        )
        assertTrue(charging.animate)

        val powerSaveAllowed = AnimatedWallpaperPolicy.resolvePlayback(
            state = animatingState().copy(powerSaveMode = true, pauseOnPowerSave = false),
            quality = AnimatedWallpaperQuality.HIGH,
        )
        assertTrue(powerSaveAllowed.animate)

        val lowBatteryAllowed = AnimatedWallpaperPolicy.resolvePlayback(
            state = animatingState().copy(batteryPercent = 5, pauseOnLowBattery = false),
            quality = AnimatedWallpaperQuality.HIGH,
        )
        assertTrue(lowBatteryAllowed.animate)
    }

    @Test
    fun dischargingBatteryBelowFortyHalvesRepaintCadence() {
        val playback = AnimatedWallpaperPolicy.resolvePlayback(
            state = animatingState().copy(batteryPercent = 30),
            quality = AnimatedWallpaperQuality.HIGH,
        )

        assertTrue(playback.animate)
        assertEquals(66L, playback.frameIntervalMs)
    }

    @Test
    fun shouldRenderFrameRespectsIntervalAndClockResets() {
        assertTrue(AnimatedWallpaperPolicy.shouldRenderFrame(0L, 40L, 33L))
        assertFalse(AnimatedWallpaperPolicy.shouldRenderFrame(100L, 120L, 33L))
        assertTrue(AnimatedWallpaperPolicy.shouldRenderFrame(100L, 133L, 33L))
        assertTrue(AnimatedWallpaperPolicy.shouldRenderFrame(200L, 100L, 33L))
        assertTrue(AnimatedWallpaperPolicy.shouldRenderFrame(100L, 100L, 0L))
    }

    @Test
    fun decodeSampleSizeKeepsEnoughPixelsForCenterCrop() {
        // A landscape 4K source must stay full resolution to cover a portrait screen sharply.
        assertEquals(
            1,
            AnimatedWallpaperPolicy.decodeSampleSize(
                sourceWidth = 3840,
                sourceHeight = 2160,
                surfaceWidth = 1080,
                surfaceHeight = 2160,
                quality = AnimatedWallpaperQuality.HIGH,
            ),
        )
        assertEquals(
            2,
            AnimatedWallpaperPolicy.decodeSampleSize(
                sourceWidth = 3840,
                sourceHeight = 2160,
                surfaceWidth = 1080,
                surfaceHeight = 1080,
                quality = AnimatedWallpaperQuality.HIGH,
            ),
        )
        // An 8K source is capped by the balanced preset's memory budget.
        assertEquals(
            4,
            AnimatedWallpaperPolicy.decodeSampleSize(
                sourceWidth = 7680,
                sourceHeight = 4320,
                surfaceWidth = 1080,
                surfaceHeight = 2160,
                quality = AnimatedWallpaperQuality.BALANCED,
            ),
        )
    }

    @Test
    fun decodeSampleSizeIsAPowerOfTwoAndHandlesBadDimensions() {
        val sample = AnimatedWallpaperPolicy.decodeSampleSize(
            sourceWidth = 6000,
            sourceHeight = 4000,
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            quality = AnimatedWallpaperQuality.BATTERY_SAVER,
        )
        assertTrue(sample > 0)
        assertEquals(0, sample and (sample - 1))
        assertEquals(
            1,
            AnimatedWallpaperPolicy.decodeSampleSize(
                sourceWidth = -1,
                sourceHeight = 100,
                surfaceWidth = 100,
                surfaceHeight = 100,
                quality = AnimatedWallpaperQuality.BALANCED,
            ),
        )
    }

    @Test
    fun renderSizeHonorsMemoryBudget() {
        val size = AnimatedWallpaperPolicy.resolveRenderSize(
            surfaceWidth = 2160,
            surfaceHeight = 3840,
            quality = AnimatedWallpaperQuality.BATTERY_SAVER,
        )

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
        assertTrue(size.width <= 2160)
        assertTrue(size.height <= 3840)
        assertTrue(
            size.width.toLong() * size.height.toLong() <=
                AnimatedWallpaperQuality.BATTERY_SAVER.maxPixels,
        )
    }

    @Test
    fun coverBoundsFillTheSurfaceWithoutDistortion() {
        val landscapeOnPortrait = AnimatedWallpaperPolicy.coverBounds(
            sourceWidth = 1920,
            sourceHeight = 1080,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertTrue(landscapeOnPortrait.bottom - landscapeOnPortrait.top >= 1920)
        assertTrue(landscapeOnPortrait.right - landscapeOnPortrait.left >= 1080)
        assertTrue(landscapeOnPortrait.left <= 0)
        assertTrue(landscapeOnPortrait.right >= 1080)

        assertEquals(
            AnimatedWallpaperRect(0, 0, 100, 100),
            AnimatedWallpaperPolicy.coverBounds(100, 100, 100, 100),
        )
    }

    @Test
    fun qualityPreferenceFallsBackToBalanced() {
        assertEquals(
            AnimatedWallpaperQuality.HIGH,
            AnimatedWallpaperQuality.fromPreference("HIGH"),
        )
        assertEquals(
            AnimatedWallpaperQuality.BALANCED,
            AnimatedWallpaperQuality.fromPreference("not-a-preset"),
        )
    }
}
