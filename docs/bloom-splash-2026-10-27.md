# 花开成形 · Android startup

The user selected the independently designed “花开成形” proposal on 2026-10-27.
The accepted visual and motion specification is in [the splash design page](../design-system/floryx/pages/splash.md).

## Implementation

- Replaced the fullscreen black/white wordmark images with a Compose flower, serif wordmark and localized tagline on the Paper theme.
- Reused the brand's petal outline as a Canvas path. Each of the six petals opens independently; the wordmark and tagline enter before the surface fades into the home.
- The animation lasts 1,100 ms at Android animation scale 1. It honors the system's animation scale and skips the overlay when animations are disabled.
- Consume the launch marker before playing, so activity recreation and restoring saved state do not replay it. While visible, the overlay consumes touches and masks the home from accessibility traversal.
- Use safe drawing insets and compact landscape spacing. Limit the wordmark to available width at large font sizes and balance tagline wrapping.
- Native starting windows use warm paper or dark paper. Android 12+ application night mode is synchronized on process startup and appearance changes, including clearing the override when following the system.
- Keep the existing version, application IDs, storage and signing configuration.

Android recommends synchronizing the application's night mode so that the system splash matches the app: [Android dark theme guidance](https://developer.android.com/develop/ui/views/theming/darktheme). The platform maps application `MODE_NIGHT_AUTO` to an undefined application override, allowing system configuration to apply: [AOSP UiModeManagerService](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/UiModeManagerService.java).

## Verification

- Debug APK and instrumentation APK built successfully.
- Complete unit run: 523 tests, 522 passed, 1 existing Windows-dependent test skipped, 0 failures/errors.
- Debug lint passed; existing dependency/deprecation warnings remain.
- MuMu instance 0, Android 15, verified with MuMuManager and targeted only as `127.0.0.1:16384`.
- Four normal-motion diagnostics passed; the disabled-motion diagnostic was gated out in that run and passed separately with animation scale 0. Five distinct device checks were exercised successfully.
- Rendering covered light, dark, system light, system dark, 320 × 640 dp at 200% font scale, 640 × 320 dp at normal and 200% font scale, and the opening flower frame. Text bounds/overflow and background colors passed.
- Interaction checks confirmed touches cannot reach the home while launching, the home is usable after dismissal, theme changes do not replay the splash, and activity recreation during playback does not replay it.
- Native day/night resource checks passed for light, dark and following the system.
- A real cold launch was recorded after installation. It showed the matching dark native background, the opening flower and wordmark, and the fade into the existing home. Android reported `LaunchState: COLD`, `Status: ok`, and first activity draw in 1,420 ms; this is separate from the animation duration.
- Restored MuMu's original animator duration scale of 0.5. Device size, density, font scale, system night mode and stored app appearance were preserved. Left the updated Floryx Dev open.

## Local artifacts

Ignored verification output is in `build/bloom-splash/`, including build/test logs, light/dark and layout captures, the theme comparison image, and the verified cold-start recording.

Installable APK: `build/releases/Floryx-Dev-1.5.19-bloom-splash.apk`.

- Identity: **Floryx Dev**, `com.flowchat.app.debug`, version **1.5.19 (33)**, debug build.
- SHA-256: `A3A692D10EC92C8E844167E5E7F2FCC488110E3FB1E6161A5ED7AAD35B3B573C`.

No real model requests were submitted by these diagnostics. The opt-in fixture renders local content and does not edit provider credentials or conversation records.

## Follow-up · 2026-10-28: blank startup and truncated motion

Phone testing exposed a long blank starting surface followed by a short visible bloom. The native icon was deliberately transparent, and the Compose clock started while the first chat composition and its dependency graph were still being prepared. The earlier visual/lifecycle checks did not protect this real cold-start handoff.

- Show the accepted flowing-f mark during native process startup, including `icon_preferred` on Android 13+.
- Draw the lightweight flower surface first and wait for the platform handoff, a drawn application frame and the resumed lifecycle before starting its timeline. Focus after a frame is a fallback for launches without a platform starting view.
- Keep a seed visible in the first application frame. Play the opening before creating the home; hold the completed brand at 740 ms while the chat screen is prepared, then finish the hold/fade after its first draw. The variable home preparation time is outside the 1,100 ms animation budget.
- Pause animation progress while the activity is not resumed. Preserve disabled-motion skipping, recreation behavior, touch blocking and appearance handling.

This follows the platform's [starting-window handoff and `icon_preferred` guidance](https://developer.android.com/develop/ui/views/launch/splash-screen).

Follow-up verification passed: both motion/resource unit contracts, Debug build and lint, six normal-motion MuMu checks (including slow-home handoff and background/resume), and the disabled-motion check in a separate run with animation scale zero. The final real cold-start recording shows the native mark, visible seed/petals, lettering and the transition to the home; Android reported first draw at 791 ms in that run, separate from the animation and home-preparation duration. Restored MuMu's original animation scale of 0.5.

The accepted test APK was installed over the V2302A phone's existing package with matching signing certificate, unchanged UID and user-data directory identity, and a byte-for-byte installed APK hash check. The phone was left stopped for the user's cold-launch test. Test APK: `build/releases/Floryx-Dev-1.5.19-startup-fix.apk`, SHA-256 `FF77167F304F665D103CC3D220C17D53D6EC64FC86877BBA39EA610C37849DD5`. Local evidence is under `build/splash-timing/` and `build/phone-install/startup-fix/`.
