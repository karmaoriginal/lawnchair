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

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Stores the selected image inside the app's private files directory.
 *
 * Keeping a local copy of an image or video means the wallpaper engine never has to re-open a
 * content provider while decoding, and it keeps working after the one-shot SAF read permission
 * expires. The path stored in preferences is validated against this directory so a restored
 * preference cannot point the engine at an arbitrary file.
 */
object AnimatedWallpaperSource {
    private const val TAG = "AnimatedWallpaperSource"
    private const val DIRECTORY = "animated_wallpaper"
    private const val FILE_NAME = "current"
    private const val COPY_BUFFER_SIZE = 16 * 1024

    fun store(context: Context, uri: Uri, mimeType: String): String? {
        var temporary: File? = null
        return try {
            val directory = File(context.filesDir, DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) return null
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val target = File(directory, FILE_NAME)
            val scratch = File(directory, "$FILE_NAME.tmp")
            temporary = scratch
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            val maxBytes = AnimatedWallpaperMedia.maxSourceBytes(mimeType, target.absolutePath)
            var copiedBytes = 0L
            input.use { source ->
                FileOutputStream(scratch).use { destination ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copiedBytes += read
                        if (copiedBytes > maxBytes) {
                            throw IllegalArgumentException("Animated wallpaper exceeds the size limit")
                        }
                        destination.write(buffer, 0, read)
                    }
                }
            }
            if (target.exists() && !target.delete()) {
                scratch.delete()
                return null
            }
            if (!scratch.renameTo(target)) {
                scratch.copyTo(target, overwrite = true)
                scratch.delete()
            }
            target.absolutePath
        } catch (e: Exception) {
            temporary?.delete()
            Log.e(TAG, "Could not store animated wallpaper", e)
            null
        }
    }

    fun resolve(context: Context, path: String): File? {
        if (path.isBlank()) return null
        val expected = File(File(context.filesDir, DIRECTORY), FILE_NAME)
        val candidate = File(path)
        val expectedPath = runCatching { expected.canonicalPath }.getOrNull() ?: return null
        val candidatePath = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        if (candidatePath != expectedPath || !candidate.isFile || !candidate.canRead()) return null
        return candidate
    }

    fun clear(context: Context) {
        val directory = File(context.filesDir, DIRECTORY)
        File(directory, FILE_NAME).delete()
        File(directory, "$FILE_NAME.tmp").delete()
    }
}
