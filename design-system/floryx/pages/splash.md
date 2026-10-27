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

Normal system animation scale: 1,100 ms total. Each stage uses cubic ease-out.

| Stage | Timing |
| --- | --- |
| Flower center fades and grows into place | 0–180 ms |
| Six petals open with 26 ms stagger | 120–500 ms |
| Wordmark fades in and rises 6 dp | 360–700 ms |
| Tagline fades in | 500–700 ms |
| Entire launch surface fades into the already composed home | 880–1,100 ms |

Honor Android animator duration scale; when animations are disabled, enter the home directly.
Start the timeline only after a splash frame has drawn, the activity is resumed, and the native starting window has handed off. Pause the timeline while the activity is not resumed.
First draw only the lightweight splash. At 740 ms, hold the completed flower and lettering while preparing the home; start the remaining hold/fade only after the home has drawn. The 1,100 ms motion duration excludes this variable preparation hold.
Play once per fresh activity launch. Restoring/recreating an activity or returning from the background does not replay the animation.
Mask underlying home accessibility and consume touches until the splash is dismissed.

The native starting window uses the latest flowing-f brand mark on Paper, with day/night colors. Android 13+ requests `icon_preferred`, so process startup does not show an empty surface. The first Compose frame retains a visible seed before the petals open.
On Android 12+, synchronize the platform's application night mode at process startup and when the saved appearance changes, so the native starting window honors the chosen app theme. Remove the platform starting view at handoff; focused launches without a platform view use a post-frame fallback. Do not show the previous black/white fullscreen wordmark before the animation.
