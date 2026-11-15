# Animated wallpapers

Lawnchair ships a battery-aware live wallpaper service for animated images. Open
**Home screen → Wallpaper → Animated wallpaper**, choose an animated GIF, WebP, APNG or HEIF
image, and use **Set as live wallpaper** to activate Lawnchair's animated wallpaper in the system
wallpaper picker. Static images are accepted too and stay as still wallpapers.

## Why it saves battery

- On Android 9+ the image is decoded once with `ImageDecoder` and animated by
  `AnimatedImageDrawable` on the platform RenderThread. There is no Java polling loop driving
  frames.
- The engine stops the animation and paints one cached bitmap when the wallpaper is hidden, the
  screen is off, Battery Saver is active, the battery is below the preset threshold, or the
  feature is disabled. A frozen frame performs no further frame decoding.
- Surface repaints are paced by the quality preset (30/24/15 fps). A discharging battery below
  40% halves the repaint cadence before the hard low-battery pause is reached.
- Decode resolution is bounded by a power-of-two sample size and a pixel budget. Center-crop
  keeps wallpaper aspect ratio intact without upscaling a landscape source unnecessarily on a
  portrait screen.
- The selected file is copied into the app's private storage (up to 128 MiB), so the engine does
  not re-open a content provider while decoding and does not depend on a one-shot SAF grant.

## Quality presets

| Preset | Max repaints | Decode budget | Low-battery pause |
| --- | --- | --- | --- |
| High quality | 30 fps | Full display scale, up to 3840 × 2160 | At or below 25% |
| Balanced | 24 fps | 85% display scale, up to ~4 MP | At or below 15% |
| Battery saver | 15 fps | 65% display scale, up to 1080p | Only at 0% (charging still respected) |

Battery Saver and low-battery pausing can be toggled independently. On Android 8.0/8.1,
`ImageDecoder` is unavailable, so the first frame is shown as a still wallpaper.

## CI and prereleases

`.github/workflows/animated_wallpaper_ci.yml` runs `spotlessCheck`, the JVM policy tests and the
`lawnWithQuickstepGithubDebug` APK build on pull requests and pushes to the feature branch. A
successful push (or manual dispatch) publishes/updates the `animated-wallpaper-ci` prerelease with
`Lawnchair.AnimatedWallpaper.CI.apk` attached. Pull requests only upload the APK as a workflow
artifact.

## Implementation map

- `app.lawnchair.wallpaper.animated.AnimatedWallpaperPolicy` — pure Kotlin policy for playback,
  frame pacing, decode sampling and center-crop bounds; covered by JVM unit tests.
- `AnimatedWallpaperService` — `WallpaperService.Engine` that decodes, starts/stops and draws.
- `AnimatedWallpaperSource` — private copy of the selected image and path validation.
- `AnimatedWallpaperPreferences` — image picker, presets and activation entry point.
