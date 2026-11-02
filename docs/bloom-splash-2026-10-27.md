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

## Follow-up · 2026-11-03: remove the leading static icon

At the user's request, removed the static flowing-f mark before the Compose bloom. Legacy starting windows now contain only the matching Paper background. Android 12+ uses an explicit transparent icon to suppress launcher-icon fallback, and Android 13+ no longer requests `icon_preferred`. The flower, lettering, timing and native-to-Compose handoff remain unchanged.

Debug and instrumentation APK builds and both startup unit checks passed. On the MuMu instance named `Floryx`, all six normal-motion diagnostics passed; the disabled-motion diagnostic was gated out. A real cold-launch recording confirmed the plain native background, opening petals, lettering and transition to home without the old static mark; first activity draw was 1,025 ms in this run. Evidence is in `build/splash-no-native-icon-2026-11-03/`. Restored the original animator duration scale of 0.5 and enabled accessibility-service list, and verified the previously enabled Floryx Dev service was bound and absent from the crash list after instrumentation and cold-launch testing. No physical phone was targeted.

## Follow-up · 2026-11-03: reduce animation frame work and request high refresh

Move the live timeline reads out of composition into the flower's Canvas and the text/overlay graphics-layer callbacks. The live path uses shared scalar timing functions, avoiding a new frame object and six-petal list on every tick. This follows the [Compose guidance on deferring state reads](https://developer.android.com/develop/ui/compose/performance/phases). The static-poster overload and existing 1,100 ms motion curve are retained.

While the splash is visible and the activity is resumed, request the highest supported refresh rate at the current resolution through the window's `preferredRefreshRate`. Restore the previous preference when pausing, dismissing or disposing the splash. This is a [window-local display hint](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate), subject to system policy. No global refresh-rate setting is changed.

Debug/instrumentation builds, both motion/resource unit checks and Debug lint passed. On the `Floryx` MuMu instance, eight normal-motion device checks passed, including 25 timeline updates with zero host/content recompositions and refresh-rate preference restoration on pause/resume and dismissal. The disabled-motion check passed separately. The original animator scale and accessibility-service list were preserved; the previously enabled Floryx Dev service was verified bound and absent from the crash list after instrumentation.

Three real cold starts per build at animation scale 1 were sampled with `dumpsys gfxinfo ... framestats`. In the stable opening window (100–600 ms after the first non-initial frame), pooled main-thread `AnimationStart`-to-`SyncQueued` median time fell from **4.901 ms** (90 frames) to **2.012 ms** (89 frames), about **59%**. Per-run medians were 4.877/4.849/4.998 ms before and 2.048/2.050/1.685 ms after. Full-startup statistics still include native/app startup, home initialization, render-thread waits and emulator scheduling; those measurements varied and are not a claim of a 59% increase in displayed FPS.

The selected emulator exposes 60 Hz and 15 Hz modes, so this verifies reduced frame work at its 60 Hz ceiling. Actual 90/120 Hz delivery needs verification on a supporting device; no physical phone was targeted. Raw captures, the old APK, analysis script and `comparison.json` are in `build/splash-frame-rate-2026-11-03/`.

## Follow-up · 2026-11-03: a slower, smoother bloom

Extended the standard motion duration from 1,100 to 1,500 ms. Each petal now opens over 500 ms with a 34 ms stagger; the overall flower opening spans 140–810 ms (670 ms, previously 380 ms). The wordmark enters at 480–920 ms, the tagline at 820–1,060 ms, and the surface fades at 1,220–1,500 ms. At 1,100 ms, hold the completed brand while preparing the home as before. Native startup and home preparation remain outside the motion budget.

Use smoothstep ease-in-out for the seed, petals, lettering and fade, giving each stage zero velocity at both endpoints. The drawing-only animation path and scoped high-refresh request are retained. Update timing-dependent diagnostics and static posters to use the shared brand-ready point.

Debug/instrumentation builds and both startup unit checks passed, including the gentle first/final petal-frame bounds and monotonic motion over the complete timeline. All eight normal-motion device checks passed on the MuMu instance named `Floryx`; the disabled-motion check passed separately. A real cold-start recording at animation scale 1 confirmed the slower opening, full-brand hold and smooth fade without a leading static icon. Evidence is in `build/splash-slower-2026-11-03/`. Restored the original animator scale of 0.5 and enabled accessibility-service list, then verified Floryx Dev's previously enabled service was bound and absent from the crash list. At scale 0.5 the motion budget is approximately 750 ms. No physical phone was targeted.
