# Animated wallpapers

Lawnchair ships a battery-aware live wallpaper service for animated images and muted videos. Open
**Home screen → Wallpaper → Animated wallpaper**, choose an animated GIF, WebP, APNG, HEIF image or
a video (for example MP4 or WebM), and use **Set as live wallpaper** to activate Lawnchair's animated
wallpaper in the system wallpaper picker. Static images are accepted too and stay as still
wallpapers.

## Why it saves battery

- On Android 9+ animated images are decoded once with `ImageDecoder` and animated by
  `AnimatedImageDrawable` on the platform RenderThread. There is no Java polling loop driving
  frames.
- Videos use a muted, looping `MediaPlayer` attached directly to the wallpaper surface with
  center-crop scaling. The platform's hardware decoder drives playback, so there is still no
  per-frame Java loop. Videos play at their native frame rate while visible.
- The engine stops animation and paints one cached bitmap when the wallpaper is hidden, the screen
  is off, Battery Saver is active, the battery is below the preset threshold, or the feature is
  disabled. For videos the decoder is detached and a still frame is decoded once with
  `MediaMetadataRetriever`; that frozen frame performs no further decoding.
- Animated-image surface repaints are paced by the quality preset (30/24/15 fps). A discharging
  battery below 40% halves that repaint cadence before the hard low-battery pause is reached.
- Frozen frames are center-cropped to the surface aspect ratio and scaled to the quality preset's
  pixel budget, so a landscape video does not get stretched onto a portrait screen. Animated-image
  decode resolution is also bounded by a power-of-two sample size.
- The selected file is copied into the app's private storage (up to 128 MiB for images and 512 MiB
  for videos), so the engine does not re-open a content provider while decoding and does not depend
  on a one-shot SAF grant.

## Quality presets

| Preset | Max animated-image repaints | Frozen-frame budget | Low-battery pause |
| --- | --- | --- | --- |
| High quality | 30 fps | Full display scale, up to 3840 × 2160 | At or below 25% |
| Balanced | 24 fps | 85% display scale, up to ~4 MP | At or below 15% |
| Battery saver | 15 fps | 65% display scale, up to 1080p | Only at 0% (charging still respected) |

Battery Saver and low-battery pausing can be toggled independently. Videos work on Android 8.0/8.1
because `MediaPlayer` is available there; on those versions `ImageDecoder` is unavailable, so an
animated image shows its first frame as a still wallpaper.

## CI and prereleases

`.github/workflows/animated_wallpaper_ci.yml` runs `spotlessCheck`, the JVM policy tests and the
`lawnWithQuickstepGithubDebug` APK build on pull requests and pushes to the feature branch. A
successful push (or manual dispatch) publishes/updates the `animated-wallpaper-ci` prerelease with
`Lawnchair.AnimatedWallpaper.CI.apk` attached. Pull requests only upload the APK as a workflow
artifact.

## Implementation map

- `app.lawnchair.wallpaper.animated.AnimatedWallpaperPolicy` — pure Kotlin policy for playback,
  frame pacing, decode sampling and center-crop bounds; covered by JVM unit tests.
- `AnimatedWallpaperMedia` — pure Kotlin MIME/video detection, private-copy size limits and
  surface-aspect crop bounds; covered by JVM unit tests.
- `AnimatedWallpaperService` — `WallpaperService.Engine` that decodes, starts/stops and draws both
  animated images and videos.
- `AnimatedWallpaperVideo` — `MediaPlayer` preparation/attachment and frozen-frame capture.
- `AnimatedWallpaperSource` — private copy of the selected image or video and path validation.
- `AnimatedWallpaperPreferences` — image/video picker, presets and activation entry point.
