# Floryx · 花开成形

Accepted 2026-10-27 after reviewing the independent and skill-assisted animated proposals.
The selected direction is the independently designed “花开成形”.

## Composition

- Inherit the Paper theme background, text and secondary text colors.
- Reuse the six-petal outline from `docs/assets/floryx-title-v2.svg`.
- An 88 dp flower sits above a 48 sp platform-serif Floryx wordmark, followed by the localized brand tagline.
- Center the group around 43% of the available safe height, with 28 dp horizontal gutters.
- In short landscape windows, center the group and reduce the flower, type and spacing. Constrain the wordmark to available width at large font scales; let the tagline wrap.
- Light petals: #E8BCAC / #F0D5BF. Dark petals: #C58F7C / #D9B497.

## Motion

Normal system animation scale: 1,500 ms total. Each stage uses smoothstep ease-in-out, with zero velocity at both endpoints.

| Stage | Timing |
| --- | --- |
| Flower center fades and grows into place | 0–240 ms |
| Six petals open with 34 ms stagger | 140–810 ms |
| Wordmark fades in and rises 6 dp | 480–920 ms |
| Tagline fades in | 820–1,060 ms |
| Entire launch surface fades into the already composed home | 1,220–1,500 ms |

Honor Android animator duration scale; when animations are disabled, enter the home directly.
Read the live timeline only from Canvas drawing and graphics-layer callbacks, so animation ticks do not recompose the splash or allocate a frame/petal list. While the splash is visible and the activity is resumed, request the highest refresh rate supported at the current display resolution. Release the window-local request on pause, dismissal or disposal; system display and power policies remain authoritative.
Start the timeline only after a splash frame has drawn, the activity is resumed, and the native starting window has handed off. Pause the timeline while the activity is not resumed.
First draw only the lightweight splash. At 1,100 ms, hold the completed flower and lettering while preparing the home; start the remaining hold/fade only after the home has drawn. The 1,500 ms motion duration excludes this variable preparation hold.
Play once per fresh activity launch. Restoring/recreating an activity or returning from the background does not replay the animation.
Mask underlying home accessibility and consume touches until the splash is dismissed.

As requested on 2026-11-03, the native starting window shows only the matching Paper background, with no static flowing-f mark before the flower. Android 12+ uses an explicitly transparent icon to prevent the launcher icon from appearing as a fallback. The first Compose frame retains a visible seed before the petals open.
On Android 12+, synchronize the platform's application night mode at process startup and when the saved appearance changes, so the native starting window honors the chosen app theme. Remove the platform starting view at handoff; focused launches without a platform view use a post-frame fallback. Do not show the previous black/white fullscreen wordmark before the animation.
