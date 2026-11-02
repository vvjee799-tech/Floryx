# Floryx Stitch design QA — 2026-11-02

## Comparison target and evidence

- Source visual truth: `C:/Users/vvjee/.codex/visualizations/2026/11/02/01a19b3d-1a64-7de0-a243-b3c2ee4b047f/floryx-stitch/screens/01.png` … `24.png`, selected 「简序·紧凑」 Google Stitch refinement.
- Implementation: native Android Compose application, **MuMu Floryx** instance, index 1 resolved by name, ADB `127.0.0.1:16416`.
- Controlled comparison viewport: 390×884dp; source and implementation both 780×1768px at density 2. Native Android system bars are excluded by the fixture root. Full-screen dialog/window captures are identified separately and include system chrome.
- Implementation screenshots: `E:/Flowchat/build/stitch-ui-qa/stitch-design/`.
- Combined source/implementation full-view comparisons: `E:/Flowchat/build/stitch-ui-qa/comparisons/01-splash.png`, `07-settings.png`, `08-conversation.png`, `09-prompt.png`, `11-edit-connection.png`, `12-memory.png`, `13-phone.png`, `14-system-assistant.png`, `15-answer-mode.png`, `16-terminal.png`, `17-workspace.png`, `18-appearance-language.png`.
- Combined detail comparisons at original pixel density: `07-settings-detail.png`, `11-edit-connection-detail.png`, `13-phone-detail.png` in that directory. They cover header typography, profile controls, provider form labels/masking, switches and connection states.
- Other rendered states: model bottom sheet, dark appearance, dark/light splash, unsaved confirmation, answer banner, floating input panel. These are captured from production components using synthetic data. The chat fixture omits app navigation icons; it verifies message and composer components rather than claiming an exact full-chat screenshot.
- Native 360dp-wide Floryx configuration was also exercised before the controlled screenshot pass. Main flows passed at that width. Portrait is the acceptance viewport; exhaustive landscape and large-font coverage are not claimed.

## Findings and iteration history

1. Initial P2: standard toggles, oversize avatar editing badges, descriptive rows and repeated section labels made the settings screens too tall. Removed repetitive labels, set 24dp content gutters/56dp rows, reduced switches, moved name editing to a trailing library icon. Post-fix evidence: `07-settings.png` and `07-settings-detail.png`.
2. Initial P2: model dropdown and rectangular send action drifted from the approved chat design. Changed selection to a scrolling bottom sheet, preserved model loading/retry callbacks, used circular send/stop and centered the model footer. Post-fix evidence: `03-chat.png`, `05-model-sheet.png`; selection and send callbacks pass.
3. Initial P2: workspace pending results and command history consumed excessive height. Compacted command rows with a separately actionable Stop button and expandable output; pending results use an inline save card. Post-fix evidence: `16-terminal.png`, `17-workspace.png`.
4. Initial P2: native system assistant used blue accents and a bold sans-serif heading. Applied the warm palette, serif brand heading, neutral fields and primary send action; preserved unchecked page-text consent. Actual background-send/stop and page-context opt-in tests pass.
5. Contrast issue found by the existing theme test: a lighter secondary text token was insufficient on elevated surfaces. Restored the darker secondary token; contrast tests pass for both themes.
6. Iteration 1 screenshots captured the old MuMu instance in landscape. Those were rejected for visual comparison. Re-captured the selected **Floryx** instance with a native MuMu custom resolution (rather than ADB logical scaling), producing equal-size portrait captures.

No unresolved P0/P1/P2 layout finding was identified in the compared design scope. This is a native adaptation of the approved design, not a claim of pixel-identical web rendering.

## Required fidelity surfaces

- **Fonts and typography:** native SansSerif Chinese body at 15sp/24sp, 20sp/28sp page headings; serif Floryx at 26sp. Combined detail views show legible labels and stable wrapping. Device font metrics and native field labels differ slightly from browser Noto Sans; native left-aligned app bars are used consistently.
- **Spacing and layout:** 24dp gutters, 56dp setting rows, flat groups, compact composer, 12dp fields, 20–24dp transient surfaces. The prompt editor uses available writing height and native labels; forms retain native save placement. System/status data and full provider catalogs remain scrollable rather than being truncated to the six illustrative rows.
- **Colors and tokens:** paper `#F9F7F2`, light field `#FDFCF9`, ink `#38352F`, olive `#645E4C`, brand `#2D604F`; dark `#24231F`/`#2C2B25`. Semantic success/error states remain distinct. Existing contrast tests pass. No new gradients/glass effects.
- **Image quality and assets:** retained project launcher/vector f mark and existing provider resources. No screenshot is used as application UI. Shared Material icons replace the hand-drawn avatar pencil. Source provider mark variants are intentionally retained for identity continuity.
- **Copy and content:** removed launch slogan, profile/helper paragraphs, duplicate setting headings, version footer and repeated instruction text. Kept permission repair, errors, command/output disclosure, unsaved-change choices and irreversible deletion confirmation. Fixture status values are not treated as layout defects when they differ from mock values. Actual workspace destination replaces fabricated file size.
- **States/interactions:** settings destinations/back, light/dark choice, prompt edits and unsaved save, memory clear confirmation, model sheet selection, input/send, floating send/stop/collapse, system-assistant background running/stopping and page-text consent verified. No paid provider calls used.
- **Accessibility:** semantic switch/radio/button roles retained, form labels and content descriptions preserved, entire setting rows remain actionable. No claim of exhaustive screen-reader, font scaling or reduced-motion validation.

## Intentional adaptations / P3 follow-up

- Native app-bar alignment and floating field labels differ from the web mock; these are consistent Android controls.
- Settings uses a destination row for answer mode so its permission status remains accessible; the switch lives on that destination.
- Minor profile whitespace, metadata typography and browser/native glyph metrics can be refined in a later visual pass.

## Verification and limitations

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`: passed. Log: `build/stitch-ui-build.log`.
- Unit result: 554 tests, 0 failures/errors, 1 skipped.
- Final selected-device result: **OK (7 tests)**. Log: `build/stitch-ui-device.log`; APK hashes and target identity: `build/stitch-ui-qa/verification.json`.
- The old `FloatingOutputDiagnosticTest` renderer fixture is **not** part of these seven passing tests. On an empty instance the production runtime overwrites its synthetic bridge state with an empty conversation. The former instance also had ADB coordinate transforms after size overrides. Its failed logs and the before-design overlay/theme baseline were retained. The production bubble touch behavior was not rewritten to accommodate that test. Whole-window pass-through regression is a separate unresolved test-coverage gap, not a passed result.
- Post-instrumentation state on Floryx: original accessibility list restored; Bound services and Crashed services both empty (service was originally disabled). No service crash is left behind.

## Implementation checklist

- [x] Use approved Stitch direction and existing product assets.
- [x] Update shared theme and screen components; keep live callbacks.
- [x] Inspect combined reference/implementation full views and detail crops.
- [x] Pass build, unit tests, Lint and selected-device behavior fixtures.
- [x] Update AGENTS.md and resolve MuMu instance by name.
- [x] Record the legacy diagnostic limitation without reporting it as passed.

final result: passed
