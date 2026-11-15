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

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import android.view.SurfaceHolder
import java.io.File

private const val VIDEO_TAG = "AnimatedWallpaperVideo"

/** Prepares a muted, looping local video on a background thread. */
internal fun prepareVideoPlayer(file: File): MediaPlayer? = try {
    MediaPlayer().apply {
        setDataSource(file.absolutePath)
        isLooping = true
        setScreenOnWhilePlaying(false)
        prepare()
        setVolume(0f, 0f)
    }
} catch (e: Exception) {
    Log.e(VIDEO_TAG, "Could not prepare video wallpaper", e)
    null
}

/** Attaches the prepared video to the wallpaper surface with center-crop scaling. */
internal fun attachVideoPlayer(player: MediaPlayer, holder: SurfaceHolder) {
    player.setDisplay(holder)
    runCatching {
        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
    }
}

/** Detaches and fully releases a video decoder when the source changes or the engine is destroyed. */
internal fun releaseVideoPlayer(player: MediaPlayer?) {
    if (player == null) return
    runCatching { player.setDisplay(null) }
    runCatching { player.stop() }
    runCatching { player.reset() }
    runCatching { player.release() }
}

/** Decodes one center-cropped, quality-bounded frame for the paused wallpaper. */
internal fun captureVideoFrame(
    file: File,
    positionMs: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
    quality: AnimatedWallpaperQuality,
): Bitmap? {
    if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
    val render = AnimatedWallpaperPolicy.resolveRenderSize(surfaceWidth, surfaceHeight, quality)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val source = retriever.getFrameAtTime(
            positionMs * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return null
        val crop = AnimatedWallpaperMedia.centerCropBounds(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = render.width,
            targetHeight = render.height,
        )
        val cropped = if (crop.right - crop.left == source.width && crop.bottom - crop.top == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        }
        if (cropped !== source) source.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, render.width, render.height, true)
        if (scaled !== cropped) cropped.recycle()
        scaled
    } catch (e: OutOfMemoryError) {
        Log.w(VIDEO_TAG, "Could not allocate a video frame", e)
        null
    } catch (e: Exception) {
        Log.w(VIDEO_TAG, "Could not capture a video frame", e)
        null
    } finally {
        runCatching { retriever.release() }
    }
}
