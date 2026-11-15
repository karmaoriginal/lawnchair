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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.lawnchair.preferences2.PreferenceManager2
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AnimatedWallpaperService"

/**
 * A live wallpaper that plays animated GIF, WebP, APNG and HEIF images.
 *
 * The animation is driven by [AnimatedImageDrawable] on the platform RenderThread, so there is no
 * per-frame Java loop. The engine stops that animation and paints one cached bitmap whenever the
 * wallpaper is hidden, the screen is off, Battery Saver is active, or the battery is low. Source
 * frames are decoded once, at a bounded resolution, and surface repaints are paced by the quality
 * preset. A static image is accepted as a still wallpaper.
 */
@SuppressLint("NewApi")
class AnimatedWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = AnimatedWallpaperEngine()

    @SuppressLint("NewApi")
    private inner class AnimatedWallpaperEngine : Engine() {
        private val appContext: Context = this@AnimatedWallpaperService.applicationContext
        private val prefs = PreferenceManager2.getInstance(appContext)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var receiverRegistered = false
        private var surfaceReady = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var wallpaperVisible = false
        private var sourceAnimated = false
        private var decodeJob: Job? = null
        private var drawable: Drawable? = null
        private var frozenFrame: Bitmap? = null
        private var lastDrawTimeMs = 0L
        private var frameIntervalMs = Long.MAX_VALUE
        private var playback = AnimatedWallpaperPlayback(
            animate = false,
            frameIntervalMs = Long.MAX_VALUE,
            reason = AnimatedWallpaperPauseReason.DISABLED,
        )
        private var config = EngineConfig(
            enabled = false,
            sourcePath = "",
            quality = AnimatedWallpaperQuality.BALANCED,
            pauseOnPowerSave = true,
            pauseOnLowBattery = true,
        )

        private val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshPlayback()
            }
        }

        private val drawableCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (!playback.animate) return
                mainHandler.post { drawFrame(force = false) }
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                mainHandler.postAtTime(what, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                mainHandler.removeCallbacks(what)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(
                appContext,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            observePreferences()
            refreshPlayback()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val frame = holder.surfaceFrame
            setSurfaceSize(frame.width(), frame.height())
            surfaceReady = true
            decodeSource()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            if (setSurfaceSize(width, height)) {
                decodeSource()
            } else {
                drawFrame(force = true)
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame(force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            decodeJob?.cancel()
            releaseDrawable()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            wallpaperVisible = visible
            refreshPlayback()
        }

        override fun onDestroy() {
            decodeJob?.cancel()
            scope.cancel()
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(stateReceiver) }
                receiverRegistered = false
            }
            releaseDrawable()
            surfaceReady = false
            super.onDestroy()
        }

        private fun observePreferences() {
            combine(
                prefs.animatedWallpaperEnabled.get(),
                prefs.animatedWallpaperSource.get(),
                prefs.animatedWallpaperQuality.get(),
                prefs.animatedWallpaperPauseOnPowerSave.get(),
                prefs.animatedWallpaperPauseOnLowBattery.get(),
            ) { enabled, sourcePath, quality, pauseOnPowerSave, pauseOnLowBattery ->
                EngineConfig(
                    enabled = enabled,
                    sourcePath = sourcePath,
                    quality = quality,
                    pauseOnPowerSave = pauseOnPowerSave,
                    pauseOnLowBattery = pauseOnLowBattery,
                )
            }
                .distinctUntilChanged()
                .onEach { newConfig ->
                    val sourceChanged = newConfig.sourcePath != config.sourcePath
                    val qualityChanged = newConfig.quality != config.quality
                    config = newConfig
                    if (sourceChanged || qualityChanged) {
                        decodeSource()
                    } else {
                        refreshPlayback()
                    }
                }
                .launchIn(scope)
        }

        private fun setSurfaceSize(width: Int, height: Int): Boolean {
            val newWidth = width.coerceAtLeast(0)
            val newHeight = height.coerceAtLeast(0)
            val changed = surfaceWidth != newWidth || surfaceHeight != newHeight
            surfaceWidth = newWidth
            surfaceHeight = newHeight
            return changed
        }

        private fun decodeSource() {
            decodeJob?.cancel()
            val capturedConfig = config
            val sourceFile = AnimatedWallpaperSource.resolve(appContext, capturedConfig.sourcePath)
            if (!surfaceReady || surfaceWidth <= 0 || surfaceHeight <= 0 || sourceFile == null) {
                releaseDrawable()
                drawFrame(force = true)
                return
            }
            val width = surfaceWidth
            val height = surfaceHeight
            decodeJob = scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            decodeWithImageDecoder(sourceFile, width, height, capturedConfig.quality)
                        } else {
                            decodeStaticDrawable(appContext, sourceFile, width, height, capturedConfig.quality)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not decode animated wallpaper", e)
                        null
                    }
                }
                val stale = capturedConfig != config ||
                    width != surfaceWidth ||
                    height != surfaceHeight ||
                    !surfaceReady
                if (stale) {
                    decoded?.let { disposeDrawable(it.drawable) }
                    return@launch
                }
                releaseDrawable()
                if (decoded == null) {
                    drawFrame(force = true)
                    return@launch
                }
                drawable = decoded.drawable
                sourceAnimated = decoded.animated
                if (sourceAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    prepareAnimatedDrawable(decoded.drawable, drawableCallback)
                }
                refreshPlayback()
                drawFrame(force = true)
            }
        }

        private fun refreshPlayback() {
            val device = readDeviceState()
            val state = AnimatedWallpaperState(
                enabled = config.enabled,
                visible = wallpaperVisible,
                screenOn = device.screenOn,
                powerSaveMode = device.powerSaveMode,
                sourceAnimated = sourceAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                batteryPercent = device.batteryPercent,
                charging = device.charging,
                pauseOnPowerSave = config.pauseOnPowerSave,
                pauseOnLowBattery = config.pauseOnLowBattery,
            )
            val decision = AnimatedWallpaperPolicy.resolvePlayback(state, config.quality)
            val previous = playback
            playback = decision
            frameIntervalMs = decision.frameIntervalMs
            if (previous == decision) return
            if (decision.animate) {
                drawable?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startAnimatedDrawable(it)
                }
                drawFrame(force = true)
            } else {
                freezeFrame()
                drawFrame(force = true)
            }
        }

        private fun readDeviceState(): DeviceState {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            val capacity = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            return DeviceState(
                screenOn = powerManager?.isInteractive != false,
                powerSaveMode = powerManager?.isPowerSaveMode == true,
                batteryPercent = capacity,
                charging = batteryManager?.isCharging == true,
            )
        }

        /** Captures the current frame before stopping the animation, so pausing costs no decoding. */
        private fun freezeFrame() {
            val current = drawable ?: return
            val size = AnimatedWallpaperPolicy.resolveRenderSize(
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                quality = config.quality,
            )
            try {
                val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                current.setBounds(0, 0, size.width, size.height)
                current.draw(Canvas(bitmap))
                frozenFrame?.takeIf { !it.isRecycled }?.recycle()
                frozenFrame = bitmap
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Could not allocate a frozen wallpaper frame", e)
            } catch (e: Exception) {
                Log.w(TAG, "Could not capture a frozen wallpaper frame", e)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                stopAnimatedDrawable(current)
            }
        }

        private fun drawFrame(force: Boolean) {
            if (!surfaceReady || surfaceWidth <= 0 || surfaceHeight <= 0) return
            val now = SystemClock.uptimeMillis()
            val shouldDraw = force || (
                playback.animate &&
                    AnimatedWallpaperPolicy.shouldRenderFrame(
                        lastRenderTimeMs = lastDrawTimeMs,
                        nowMs = now,
                        frameIntervalMs = frameIntervalMs,
                    )
                )
            if (!shouldDraw) return
            lastDrawTimeMs = now

            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "Could not lock the wallpaper surface", e)
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                val frame = holder.surfaceFrame
                val viewWidth = frame.width()
                val viewHeight = frame.height()
                val current = drawable
                val frozen = frozenFrame
                when {
                    playback.animate && current != null -> {
                        val bounds = coverRect(
                            sourceWidth = current.intrinsicWidth,
                            sourceHeight = current.intrinsicHeight,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                        current.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        current.draw(canvas)
                    }

                    frozen != null && !frozen.isRecycled -> {
                        val bounds = coverRect(
                            sourceWidth = frozen.width,
                            sourceHeight = frozen.height,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                        canvas.drawBitmap(frozen, null, bounds, paint)
                    }

                    current != null -> {
                        val bounds = coverRect(
                            sourceWidth = current.intrinsicWidth,
                            sourceHeight = current.intrinsicHeight,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                        current.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        current.draw(canvas)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not draw the wallpaper", e)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unlock the wallpaper surface", e)
                }
            }
        }

        private fun coverRect(
            sourceWidth: Int,
            sourceHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): Rect {
            val bounds = AnimatedWallpaperPolicy.coverBounds(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
            return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

        private fun releaseDrawable() {
            drawable?.let(::disposeDrawable)
            drawable = null
            frozenFrame?.takeIf { !it.isRecycled }?.recycle()
            frozenFrame = null
            sourceAnimated = false
            lastDrawTimeMs = 0L
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(
    file: File,
    surfaceWidth: Int,
    surfaceHeight: Int,
    quality: AnimatedWallpaperQuality,
): DecodedWallpaper {
    val source = ImageDecoder.createSource(file)
    var headerAnimated = false
    val decoded = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
        headerAnimated = info.isAnimated
        val sample = AnimatedWallpaperPolicy.decodeSampleSize(
            sourceWidth = info.size.width,
            sourceHeight = info.size.height,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            quality = quality,
        )
        if (sample > 1) decoder.setTargetSampleSize(sample)
        // A software allocation can be drawn into the cached frozen-frame bitmap and works for
        // both AnimatedImageDrawable and static BitmapDrawable results.
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        if (quality != AnimatedWallpaperQuality.HIGH) {
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
        }
    }
    return DecodedWallpaper(
        drawable = decoded,
        animated = headerAnimated && decoded is AnimatedImageDrawable,
    )
}

private fun decodeStaticDrawable(
    context: Context,
    file: File,
    surfaceWidth: Int,
    surfaceHeight: Int,
    quality: AnimatedWallpaperQuality,
): DecodedWallpaper {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val sample = AnimatedWallpaperPolicy.decodeSampleSize(
        sourceWidth = bounds.outWidth,
        sourceHeight = bounds.outHeight,
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        quality = quality,
    )
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        ?: throw IllegalStateException("Could not decode wallpaper image")
    return DecodedWallpaper(
        drawable = BitmapDrawable(context.resources, bitmap),
        animated = false,
    )
}

@RequiresApi(Build.VERSION_CODES.P)
private fun prepareAnimatedDrawable(drawable: Drawable, callback: Drawable.Callback) {
    (drawable as? AnimatedImageDrawable)?.apply {
        setFilterBitmap(true)
        repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        setCallback(callback)
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun startAnimatedDrawable(drawable: Drawable) {
    (drawable as? AnimatedImageDrawable)?.let {
        if (!it.isRunning) it.start()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun stopAnimatedDrawable(drawable: Drawable) {
    (drawable as? AnimatedImageDrawable)?.let {
        if (it.isRunning) it.stop()
    }
}

private fun disposeDrawable(drawable: Drawable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        stopAnimatedDrawable(drawable)
    }
    drawable.setCallback(null)
    (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.recycle()
}

private data class DecodedWallpaper(
    val drawable: Drawable,
    val animated: Boolean,
)

private data class DeviceState(
    val screenOn: Boolean,
    val powerSaveMode: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
)

private data class EngineConfig(
    val enabled: Boolean,
    val sourcePath: String,
    val quality: AnimatedWallpaperQuality,
    val pauseOnPowerSave: Boolean,
    val pauseOnLowBattery: Boolean,
)
