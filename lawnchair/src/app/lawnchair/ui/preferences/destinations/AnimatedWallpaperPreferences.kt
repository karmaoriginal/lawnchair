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

package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.controls.WarningPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.wallpaper.animated.AnimatedWallpaperQuality
import app.lawnchair.wallpaper.animated.AnimatedWallpaperService
import app.lawnchair.wallpaper.animated.AnimatedWallpaperSource
import com.android.launcher3.R
import com.android.launcher3.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AnimatedWallpaperPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs2 = preferenceManager2()
    val scope = rememberCoroutineScope()
    val enabledAdapter = prefs2.animatedWallpaperEnabled.getAdapter()
    val sourceAdapter = prefs2.animatedWallpaperSource.getAdapter()
    val mimeTypeAdapter = prefs2.animatedWallpaperMimeType.getAdapter()
    val qualityAdapter = prefs2.animatedWallpaperQuality.getAdapter()
    val enabled by enabledAdapter
    val sourcePath by sourceAdapter

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val isSupported = mimeType.startsWith("image/", ignoreCase = true) ||
            mimeType.startsWith("video/", ignoreCase = true)
        if (!isSupported) {
            Toast.makeText(context, R.string.animated_wallpaper_file_type_error, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                AnimatedWallpaperSource.store(context.applicationContext, uri, mimeType)
            }
            if (path == null) {
                Toast.makeText(context, R.string.animated_wallpaper_file_error, Toast.LENGTH_SHORT).show()
            } else {
                mimeTypeAdapter.onChange(mimeType)
                sourceAdapter.onChange(path)
                enabledAdapter.onChange(true)
            }
        }
    }
    val pickIntent = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("image/*", "video/*"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    PreferenceLayout(
        label = stringResource(id = R.string.animated_wallpaper_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(
            description = stringResource(id = R.string.animated_wallpaper_battery_description),
        ) {
            SwitchPreference(
                adapter = enabledAdapter,
                label = stringResource(id = R.string.animated_wallpaper_enable),
                description = stringResource(id = R.string.animated_wallpaper_enable_description),
            )
        }

        ExpandAndShrink(visible = enabled) {
            PreferenceGroup(heading = stringResource(id = R.string.animated_wallpaper_source_group)) {
                ClickablePreference(
                    label = stringResource(id = R.string.animated_wallpaper_choose_file),
                    subtitle = stringResource(
                        id = if (sourcePath.isBlank()) {
                            R.string.animated_wallpaper_no_file
                        } else {
                            R.string.animated_wallpaper_file_selected
                        },
                    ),
                    onClick = { pickImage.launch(pickIntent) },
                )
                ClickablePreference(
                    label = stringResource(id = R.string.animated_wallpaper_activate),
                    subtitle = stringResource(id = R.string.animated_wallpaper_activate_description),
                    onClick = { openWallpaperPicker(context) },
                )
                if (sourcePath.isNotBlank()) {
                    ClickablePreference(
                        label = stringResource(id = R.string.animated_wallpaper_clear_file),
                        confirmationText = stringResource(id = R.string.animated_wallpaper_clear_file_confirmation),
                        onClick = {
                            AnimatedWallpaperSource.clear(context.applicationContext)
                            mimeTypeAdapter.onChange("")
                            sourceAdapter.onChange("")
                            enabledAdapter.onChange(false)
                        },
                    )
                }
                if (!Utilities.ATLEAST_P) {
                    WarningPreference(
                        text = stringResource(id = R.string.animated_wallpaper_requires_android_p),
                    )
                }
            }

            PreferenceGroup(heading = stringResource(id = R.string.animated_wallpaper_quality_group)) {
                ListPreference(
                    adapter = qualityAdapter,
                    entries = listOf(
                        ListPreferenceEntry(
                            value = AnimatedWallpaperQuality.HIGH,
                            label = { stringResource(id = R.string.animated_wallpaper_quality_high) },
                        ),
                        ListPreferenceEntry(
                            value = AnimatedWallpaperQuality.BALANCED,
                            label = { stringResource(id = R.string.animated_wallpaper_quality_balanced) },
                        ),
                        ListPreferenceEntry(
                            value = AnimatedWallpaperQuality.BATTERY_SAVER,
                            label = { stringResource(id = R.string.animated_wallpaper_quality_battery_saver) },
                        ),
                    ),
                    label = stringResource(id = R.string.animated_wallpaper_quality),
                    description = stringResource(id = R.string.animated_wallpaper_quality_description),
                )
                SwitchPreference(
                    adapter = prefs2.animatedWallpaperPauseOnPowerSave.getAdapter(),
                    label = stringResource(id = R.string.animated_wallpaper_pause_power_save),
                )
                SwitchPreference(
                    adapter = prefs2.animatedWallpaperPauseOnLowBattery.getAdapter(),
                    label = stringResource(id = R.string.animated_wallpaper_pause_low_battery),
                    description = stringResource(id = R.string.animated_wallpaper_pause_low_battery_description),
                )
            }
        }
    }
}

private fun openWallpaperPicker(context: Context) {
    val component = ComponentName(context, AnimatedWallpaperService::class.java)
    val directIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val started = runCatching { context.startActivity(directIntent) }.isSuccess ||
        runCatching { context.startActivity(chooserIntent) }.isSuccess
    if (!started) {
        Toast.makeText(context, R.string.animated_wallpaper_picker_error, Toast.LENGTH_SHORT).show()
    }
}
