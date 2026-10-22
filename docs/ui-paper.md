# Paper UI — 1.5.3

Implemented the user's selected second proposal, “纸页”, on 2026-10-22. The design uses a warm paper background, serif FlowChat wordmark, flat settings rows, restrained separators, and generous spacing. The selected specification is in `design-system/flowchat/MASTER.md`.

## Changes

- Removed empty-chat introductory copy and decorative feature cards.
- Simplified message presentation; timestamps are available from message actions, and completed tool details are collapsed by default.
- Kept attachment and thinking controls inside the composer, with the model selector below it.
- Simplified the conversation drawer and grouped settings by capability and preference.
- Applied the palette and spacing to provider, memory, phone-operation, and command-environment settings. Permission disclosures and error messages remain available.
- Preserved the existing conversation, attachment, memory, reasoning, automation, and terminal behavior. No live model API calls were made for this UI verification.

## Verification

- Debug, instrumentation, and release APK builds succeeded with the local retain-existing-install Gradle init script.
- Unit tests: 287 discovered, 286 passed, 1 skipped, 0 failures/errors. The skipped case requires Windows symlink privileges.
- MuMu instance 0 was identified through its CLI and targeted explicitly as `127.0.0.1:16384`. No physical phone was used.
- Instrumentation interaction checks passed at 360 × 640 dp and 400 × 812 dp. They cover draft input/send callbacks, thinking controls, attachment entry, message actions, provider settings and save guards, command-output expansion, and settings scrolling.
- A 320 × 640 dp layout with 200% font scaling was captured and visually checked. This matrix entry is a layout check, not a second full interaction run.
- Light/dark screens and settings were inspected. Live app navigation through the drawer, settings, and appearance chooser also succeeded.
- **Keyboard limitation:** MuMu's computer-input mode reports the IME as visible with a zero-height frame. Focus, typing, and control visibility passed, but full software-keyboard occlusion has not been verified.
- Restored the emulator to physical 1440 × 2560, density 640, font scale 1.0, and `show_ime_with_hard_keyboard=0`. Removed the diagnostic test package. Left FlowChat Dev 1.5.3 installed and open in light appearance for review.

Logs and captures are in the ignored `build/paper-ui/` directory. `matrix-captures/paper-preview-paper-light.png` shows real Compose components rendered in MuMu using isolated sample conversation data. `chat-final-light.png`, `settings-final-light.png`, and `drawer-final-light.png` show the installed app itself.

## Local APKs

| Artifact | Identity | SHA-256 |
| --- | --- | --- |
| `build/releases/FlowChat-1.5.3-retain-config.apk` | Release build, FlowChat, `com.flowchat.app.debug`, version 1.5.3 (17) | `55984144731efb6957782bac52783f2c80c5921d9fb3445aa5b4d449fa0293d6` |
| `build/releases/FlowChat-Dev-1.5.3.apk` | Debug build, FlowChat Dev, same package and version | `0dfba6a4f8b0bcb47c7c388b70e47fe3f585a9c4663ce6c4c04d4c72495e4069` |

The retain-config release deliberately uses the existing package ID and Android debug certificate so it can update the user's current installation without clearing data. Its certificate SHA-256 is `423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772`. The release APK is not debuggable. Install as an update; do not uninstall or clear the existing app first.
