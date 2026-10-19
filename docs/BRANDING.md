# DumbyLuba identity — 0.9.0

Gradle project name and Android display name: **DumbyLuba**. The Android application ID is kept as `fr.luba.poc`, so the existing installation is updated and its data preserved. Internal class names, the working folder and the historical APK archives keep their existing paths.

## Visual identity

A smiling robot with a tuft of grass, built from native vectors. Forest background `#10291F`, mint shell `#A7F3B9`, green sprout `#68D991` and cream details. No external bitmap and no extra dependency.

- `app/src/main/res/drawable/dumby_mark.xml`: symbol shared by the icon and the home screen.
- `mipmap-anydpi-v26/ic_launcher.xml`: adaptive icon; Android 13 variant with a monochrome mask in `mipmap-anydpi-v33`.
- `drawable/dumby_splash_animated.xml`: animated grass and blinking eyes on the Android 12+ system splash screen.
- `ui/brand/DumbyBrand.kt`: Compose intro on the forest background, progressive drawing of a mowing route, slight robot movement, the name "DumbyLuba", the tagline "Your garden, simply." and "Tap to continue" at the bottom. About 1.4 s (1,050 ms drawing, 150 ms pause, 180 ms fade-out), then a 220 ms crossfade to the home screen; a tap skips it. When animations are disabled in Android (animator duration scale 0), the app goes straight to the home screen. A recreation with saved state does not replay the intro. The tagline and the hint follow the [interface language](features/interface.md#language).
- Brand colors in code: `DumbyBrand.Forest` `#10291F`, `DumbyBrand.Mint` `#A7F3B9`, `DumbyBrand.Cream` `#F0FFF3`.

The intro does not command the robot and waits for no network call.

## Validation

Validation at the time of 0.9.0 (the current version is the `versionName` in `app/build.gradle.kts`).


Debug build, 103 JVM tests and lint without errors. Installed and launched on the Fold SM-F971B, versionCode 28, version `0.9.0-dumbyluba`.

The home screen shows DumbyLuba and restores the cached map (7 zones). A cold start was recorded to check the animation steps and the arrival on the home screen. Local screenshots and video in `artifacts/ui/0.9.0/` (not versioned). Older Android versions and the various launchers were not checked on a device; reduced animations and recreation rely on the code guards, without a dedicated instrumented test.

Reference for the system splash resources and constraints: [Android documentation](https://developer.android.com/develop/ui/views/launch/splash-screen).
