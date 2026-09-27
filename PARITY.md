# Base iOS parity work — Android 1.0.1

Scope: the supplied Kotlin/OpenGL Android port, original levels only. Reference: base iOS iQuarters commit 4798e18. No World 2, Realism, Dank, or added antialiasing.

## Changes

- Ported C# `TimedFlickGesture` mathematics: timestamped 60 Hz reference windows, batched MotionEvent history, final finger-up sample, duplicate timestamp rejection, and cancellation. Rendering refresh no longer determines swipe strength. The existing Kotlin physics solver and original collision meshes remain in use.
- Removed the fixed 640×960 rendering surface and emulated iPhone bezel/home button. Render at the device surface resolution, preserving scene framing and original UI proportions.
- Adapted top/bottom HUD anchors, button hit coordinates, and full-screen menu/pause backgrounds using the same layout approach as the base iOS version. Loading text remains bottom-left. Includes a 600×1024 Fire 7 coordinate regression check.
- Corrected camera smoothing to rebuild a world-up orientation, preventing interpolation roll.
- Reset input and wall-clock accumulation after suspension. Preserve the GL context where supported, release old scene audio on scene changes, and retain preloaded SoundPool clips.
- Added conservative whole-collider bounds rejection before the existing per-piece collision tests. Contact resolution, collision shapes, restitution, and friction are unchanged.
- Compatibility work: API 19 minimum, guarded modern display/audio APIs, legacy launcher icon. Target API 35. No Google Play services dependency or native ABI-specific libraries.

## Already present in the supplied source, retained

- Original 12 levels and secret round; recovered animations and menu transitions.
- Score glow (`lightray`) and glass flash/scale animation.
- Completion jingle restricted to the last successful Classic shot, not every score (`QuarterTrigger.shotInAir`).
- Per-level replay camera selection, including the overhead camera (`GetValidReplayCamera`).
- Original pause menu background and Loading text, with in-engine scene changes rather than platform screen transition animations.
- Original fixed-function shader lighting and transparent materials; no PBR or extra graphics effects.

## Verification and limits

Release APK build and Android lint completed successfully. Fifteen JVM tests passed (nine existing runtime/replay/particle checks and six added timing/layout/camera/data checks). APK v1 and v2 signatures verified. Native C# tests from the iOS project are not represented as Android tests.

No Android device or emulator was connected. Fire 7 (2019) launch, actual GPU rendering, audio output, and sustained frame rate remain unverified. This is a sideload testing build; full visual/physical-device parity is not yet established. Android 4.4 is the declared minimum and passes the API audit, not a claim of completed testing on that OS.

The project retains its supplied debug signing configuration. Preserve the same signing key for updates; it is not a production release key. An existing installation signed with your friend's different key will not accept an in-place update from this APK.
