# iQuarters for Android

The original iQuarters game, reconstructed for Android from the supplied Kotlin source and recovered game assets. Includes the original 12 rounds, secret round, scoring effects, replays, and animated menus.

## Version 1.0.2

Download the APK from **[Releases](https://github.com/strawberrypoptarts/iQuarters-android/releases/tag/v1.0.2)** and sideload it.

This release fixes menu artwork leaking onto tall screens, including the extra small logo and the off-screen leaderboard name banner. Menu animations are clipped to their original stage; the background and game scene still fill the device display, and gameplay controls retain their edge anchors.

Includes the blue iOS icon with adaptive-icon padding. Version code 5 supports an update over previous releases when signed with the same key.

- Android 4.4 / API 19 minimum; targets Android 15 / API 35.
- Native device resolution, touch-rate-independent flicks, and adapted menus.
- No Unity runtime or Google Play services.
- Original levels only; no Plus, Realism, or Dank content.

## Build

Requires JDK 17 and Android SDK platform/build tools 35. Set `ANDROID_HOME`, then run:

```sh
./gradlew testDebugUnitTest assembleRelease lintRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`. Package: `com.guille.iquarters`.

## Project and testing

Built on the supplied Android reconstruction, preserving its Kotlin scripts, physics, and OpenGL renderer. See [PARITY.md](PARITY.md) for the earlier compatibility changes and verification limits.

The APK retains the existing testing signature for installation continuity. A build signed with a different key cannot update it in place. Physical-device performance and rendering still need testing, including on the Fire 7 (2019).
