# Floryx · App icon

Accepted 2026-10-27: the user supplied the green, sweeping f with a fine horizontal bar on warm paper as the latest app icon.

## Source and placement

- Canonical artwork: [`../icon/source.png`](../icon/source.png), copied unchanged from the user's 1254 × 1254 PNG.
- Keep the supplied silhouette, paper background, botanical green and subtle texture.
- Launcher assets: `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`, at 48, 72, 96, 144 and 192 px.
- Scale the supplied square to 84% on matching paper, so the fine diagonal terminals remain inside circular launcher and avatar masks.
- The default profile avatar and floating assistant reuse these resources. The floating assistant keeps the original colors in light and dark themes.
- The notification uses the same silhouette in the platform's monochrome tint via `drawable/ic_launcher_foreground.xml`.

The concept directories are historical alternatives. Use this accepted artwork for app-icon updates.

## Verification · 2026-10-28

- The archived source is byte-for-byte identical to the supplied PNG (SHA-256 `CD734A07D6C487CEC748BE632DFE203EF371419FFCC9D653A3AFFD061FF124C5`).
- All five launcher densities decode at their expected sizes. The mark fits within 92% of a circular mask's radius. The traced notification silhouette overlaps the source mask by 99.28% (intersection over union).
- Debug APK, instrumentation APK and Debug lint built successfully. Lint reported zero errors.
- Updated Floryx Dev on MuMu Android 15, explicitly targeted as `127.0.0.1:16384`. The actual launcher shows the new icon with complete terminals.
- The existing floating-output diagnostic passed in dark appearance: the mark stays green on paper, circular corners remain transparent, and expand/collapse plus foreground/background restoration pass. The diagnostic restores the previous appearance and floating-assistant state.
- A real cold launch completed successfully. Verification images and logs are under the ignored `build/app-icon/` directory.

Installable Debug APK: `build/releases/Floryx-Dev-1.5.19-icon.apk` (`com.flowchat.app.debug`, version 1.5.19 / 33).
SHA-256: `2422D4C84AE5C00FF4C732B8D6A9CC28BCE486DFF58C5EF96C07EC174D89D9D5`.
