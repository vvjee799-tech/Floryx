# Floryx Ink Native Design QA

## Scope

- Source visual truth path: `C:\Users\LENOVO\.codex\generated_images\019ee4c3-8133-7a41-bcf2-82ab13fce493\ig_067b409f6960efa9016a43fb2669c08191bc03599ff391b8cf.png`
- Implementation comparison: `E:\Codex\works\FlowChat\build\flowchat-redesign-qa-comparison.png`
- Viewport: LDPlayer `emulator-5554`, portrait, debug build, dark theme.
- State: existing local conversation with drawer, conversation settings, app settings, and provider settings checked.

## Screens Checked

- Main chat: `E:\Codex\works\FlowChat\build\flowchat-redesign-main.png`
- Left drawer: `E:\Codex\works\FlowChat\build\flowchat-redesign-drawer-v2.png`
- User profile sheet: `E:\Codex\works\FlowChat\build\flowchat-redesign-profile.png`
- App settings sheet: `E:\Codex\works\FlowChat\build\flowchat-redesign-app-settings.png`
- Conversation settings sheet: `E:\Codex\works\FlowChat\build\flowchat-redesign-conversation-settings.png`
- Provider settings: `E:\Codex\works\FlowChat\build\flowchat-redesign-provider-v2.png`

## Findings

- Passed: left drawer now follows the option 2 structure more closely with a fixed-width dark drawer, Floryx header, profile entry, provider/settings navigation, conversation history, selected conversation card, and a blue floating create button.
- Passed: drawer top-right settings icon was removed. User profile and app settings no longer share one sheet.
- Passed: user profile sheet now contains only avatar, name, and save.
- Passed: app settings sheet now contains only common items: language and version. Provider, background mode, and app usage access entries were removed from this sheet.
- Passed: conversation settings keeps one editable AI avatar only, plus nickname, avatar visibility switch, system prompt, sliders, and save.
- Passed: provider preset and current-provider icons now use image resources instead of hand-drawn Canvas glyphs. OpenAI, Claude, DeepSeek, and Gemini assets are rendered from downloaded logo resources.

## Required Fidelity Surfaces

- Fonts and typography: still uses the app's Compose typography rather than an exact custom font from the mock. Hierarchy, weights, and line spacing are close enough for this implementation pass.
- Spacing and layout rhythm: drawer width, cards, modal sheets, and bottom composer now follow the reference structure. Some vertical differences remain where later product requirements removed preset avatar choices.
- Colors and visual tokens: dark surface, deep gray cards, blue primary action, and low-contrast dividers match the reference direction.
- Image quality and assets: provider icons now use real raster resources instead of code-drawn placeholders. The Gemini icon uses a public Google Gemini icon resource; OpenAI uses OpenAI status favicon; Claude and DeepSeek use their official-domain assets.
- Copy and content: implementation uses real app data and Chinese localization rather than the static mock copy.

## Patches Made Since Previous QA

- Removed drawer header gear.
- Split user profile editing and app settings into separate sheets.
- Removed provider/background/usage-access entries from app settings.
- Removed extra assistant avatar placeholders in conversation settings.
- Replaced provider Canvas glyphs with logo image resources.
- Fixed drawer width so it leaves a visible right-side app layer like the reference.
- Rebuilt and installed debug APK on LDPlayer for screenshot verification.

## Open Questions

- The source mock shows multiple assistant avatar presets, but the product requirement explicitly says not to provide preset images. This QA treats the single-avatar version as intentional.
- User profile editing is implemented as a bottom sheet, not a full-screen account page. It satisfies the current "only avatar and name" requirement; a full-screen account page would be a separate follow-up.

## Implementation Checklist

- No P0/P1/P2 issues remain for the requested structural redesign.
- P3 polish remains possible around exact typography density and provider-logo sizing.

## Result

final result: passed

---

# Floating Assistant Design QA

## Scope

- Reference: `C:\Users\LENOVO\.codex\generated_images\019ee4c3-8133-7a41-bcf2-82ab13fce493\exec-ee1b6562-22ae-4abb-ae6f-6bcc87414849.png`
- Implementation: `E:\Codex\works\FlowChat\flowchat-overlay-refined2-live.png`
- Comparison: `E:\Codex\works\FlowChat\build\flowchat-overlay-design-comparison.png`
- State: dark external app, expanded assistant, active device tool, streaming response, stop control visible.
- Viewport height: 1920 px for both captures.

## Findings

- P0: none.
- P1: none.
- P2: none.
- P3: the emulator launcher content and aspect ratio differ from the generated short-video reference; the overlay proportions, hierarchy, colors, controls, and interaction state remain aligned.
- Interaction checks: bubble tap, vertical drag, expand, collapse, composer input, send, tool-triggered auto-collapse, reopen, and stop were verified on `emulator-5554`.

## Result

final result: passed

## 2026-10-25 · 输入框一体底栏（1.5.12）

第三套去余额角标方案已完成；深浅色组件截图与三项 MuMu 回归通过。详细记录：[输入框一体底栏验收](docs/composer-footer-2026-10-25.md)。

final result: passed

## 2026-10-25 · 手机文件夹工作区

沿用现有 Paper 设置样式，检查了 MuMu 真实组件的 `build/workspace-Light.png` 和 `build/workspace-Dark.png`：标题、说明、文件夹名称与路径、文字访问状态、操作按钮及默认值开关层级清晰，无截断、重叠或横向溢出；48 dp 操作区域和原生语义保留，错误和保存回执采用无障碍实时播报。

真实系统文件夹选择、模型文件操作、脚本结果写回、停止后接续、授权失效恢复与会话关联验证通过。新增记录和实际验证范围见 [工作区说明与验证](docs/workspace-2026-10-25.md)。系统文件夹选择器沿用 Android 原生界面。

final result: passed
