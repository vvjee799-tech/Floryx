# Floryx · Paper

Accepted direction: proposal B, “纸页”, 2026-10-22. Reference boards and the clickable prototype are in `build/design-proposals/`.

## Visual language

Warm paper, quiet olive controls, generous reading space. Keep the interface free of welcome paragraphs, suggestion cards and repeated help copy.

- Light: background #F9F7F2, input #FDFCF9, text #38352F, accent #645E4C.
- Dark: background #24231F, input #2C2B25, text #E8E4D9, accent #D8CFAF.
- Shared tokens: `ui/theme/Theme.kt` and `presentation/common/FlowComponents.kt`.
- Reading gutter 28 dp; input gutter 20 dp; maximum content width 720 dp.
- Wordmark: platform serif, 28 sp. Chinese and conversation text use the native sans-serif face.
- App icon: use the accepted [green flowing f on paper](pages/icon.md), selected 2026-10-27. Preserve its colors in both themes.
- Startup: use the accepted [花开成形 splash](pages/splash.md), with six opening petals and a serif wordmark.
- Conversation body: 16/29 sp. Secondary content: 14/24 sp. Labels: 12/18 sp.
- Input radius 17 dp; send icon container 36 dp with a 48 dp touch target.
- Settings groups are flat; spacing provides hierarchy. Use borders only for fields, the composer and selected-state cues.

## Placement and behavior

- Header: menu, Floryx wordmark, new chat. The title opens conversation settings and shows a subtle chevron.
- Empty conversation: blank paper. Only offer setup when no usable conversation exists.
- Messages: user content is right-aligned with a fine underline. Assistant content stays on the page. Copy and more actions appear below completed replies; timestamps remain in the message menu.
- Composer: text above attachment, thinking and send/stop. Model selection is underneath, right-aligned. Long model names truncate in the trigger and remain available in the menu.
- Drawer: plain new-chat row, conversation titles, profile and settings in a single footer. Profile avatar/name are display-only; the separate settings button opens settings. Selected conversations have a narrow vertical marker.
- Settings: full-screen pages with a fixed top bar and back button. Profile avatar and name edit independently in settings; avatar controls share the corner pencil badge. Show labels and current states, with detail inside each destination.
- Conversation settings: assistant profile, conversation rules and reply settings. Edit system prompts on their own page, keep changes as a draft until Save, and offer save/discard/continue when returning with changes. Stack setting values below labels at large text sizes.
- Memory and command records: flat lists, expandable detail. Keep the memory switch, deletion confirmation, AI-command disclosure and stop actions.
- Providers: current connection, original brand icons, presets and collapsible custom form. Keep API keys masked and field errors explicit.
- Floating assistant: inherits the paper palette; passive viewing and touch-through behavior are preserved.

## Verification

Use MuMu, targeted explicitly by its serial. Check light/dark, small screens, large text and the software keyboard. Isolated fixtures exercise UI without invoking a model API or changing stored provider configuration. Evidence for this implementation is under `build/paper-ui/`.
