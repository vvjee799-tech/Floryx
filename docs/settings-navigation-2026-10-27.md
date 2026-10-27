# Independent settings pages — 2026-10-27

App settings and conversation settings now open as full-screen destinations with a fixed back button. Settings content scrolls within the page; there is no draggable sheet or drag-to-dismiss gesture. Memory, phone operation, terminal and workspace settings use the same page shell. Providers, terminal and workspace return to their parent settings destination.

The drawer footer displays the user avatar and name without click or long-press actions. In app settings, the avatar and name have independent controls: the shared corner pencil opens the image picker, and tapping the name opens a focused name editor. Their writes update separate preference fields, preventing an avatar write from restoring an older name. Preferences are observed so the drawer and chat refresh after navigation.

Conversation settings group the assistant profile, conversation rules and output limit. The prompt editor is a separate page. Drafts survive prompt editing and configuration changes, and leaving with changes offers save, discard or continued editing. Output limits accept a positive Int or an empty default; overflow and non-positive values cannot be submitted. Avatar imports receive unique file names so previews refresh and discarding a replacement does not overwrite the saved image.

Settings navigation shares the existing chat ViewModel, preserving the composer and active reply state. Large system fonts move setting values below their labels to avoid collisions. The paper palette and light/dark tokens are retained.

## Verification

Evidence is stored in `build/settings-redesign/`. `SettingsNavigationDiagnosticTest` uses isolated preferences and images for edits, and separately exercises the installed app's navigation and composer preservation. `MemorySettingsDiagnosticTest` verifies toggling, editing, deleting and clearing memory summaries after the page conversion. The checks do not submit chat messages or run terminal commands.

- Debug app and instrumentation APK builds passed. `:app:testDebugUnitTest` discovered 522 tests: 521 passed, 1 skipped, 0 failures/errors. `:app:lintDebug` passed with 0 errors; no warnings point to the changed settings code.
- MuMu passed the two settings tests and the memory test at 360 × 640 dp, plus the settings edit flow at 320 × 640 dp and 640 × 360 dp. Light/dark layouts and font scales 1.0/2.0 were captured and inspected. The large-text output label/value bounds are also checked for overlap.
- Screenshots: `phone-360/`, `phone-320/` and `landscape-final/`. Logs: `device.log`, `device-320.log`, `device-landscape.log` and `verification-final.log`.
- The installed APK and `Floryx-Dev-settings-pages.apk` both have SHA-256 `8baf354e52e12f8b7f5de6c30c82d8e94e219ae55efec498195f976a57b58afe`.

MuMu instance 0 was verified through MuMuManager and targeted explicitly as `127.0.0.1:16384`. The debug app is installed as Floryx Dev, package `com.flowchat.app.debug`. Its original physical size (1440 × 2560), density (640), font scale (1.0) and hardware-keyboard IME setting (0) are restored; the installed app is open for review.

MuMu's computer-input configuration reports input focus without a visible software-keyboard frame, including with `show_ime_with_hard_keyboard=1`. Text editing and fixed controls were checked; full software-keyboard occlusion could not be visually verified. The setting is restored to 0 after the check.

## Release follow-up

These settings changes are included in Floryx 1.5.19 (33). The signed, non-debuggable Release APK was subsequently installed over the same MuMu package, preserving its installation and data-directory identity. Cold launch, both settings pages and back navigation passed on the optimized release. Its installed and local APK SHA-256 is `526f058f60ff033ade3168586a68a1cab830bc271d13f796473c6877a6be7e3f`; see [release notes](releases/1.5.19.md).
