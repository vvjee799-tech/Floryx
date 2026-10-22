# FlowChat command runtime

FlowChat uses these independent open source components:

- PRoot 5.1.107.98, copyright STMicroelectronics and upstream contributors; GPL terms in licenses/proot-proot-5.1.107.98_COPYING.txt.
- talloc 2.5.0, copyright Andrew Tridgell and contributors; LGPL-3.0-or-later in licenses/talloc-talloc-2.5.0_LICENSE.txt, together with licenses/GPL-3.0.txt.
- libandroid-shmem 0.7, copyright Termux contributors; BSD-3-Clause in licenses/libandroid-libandroid-shmem-0.7_LICENSE.txt.
- Alpine Linux 3.24.2 minirootfs, an aggregation of separately licensed packages. Versions/licenses are listed in alpine-packages.json, with package metadata retained in the distribution.
- Apache Commons Compress 1.28.0; Apache-2.0, see the corresponding license and notice files in licenses/.

Native corresponding sources, applicable Termux build recipes and full license texts are included in this APK at assets/terminal/native-sources.zip. They can be extracted with any ZIP/APK archive tool. Binary/source versions, original download URLs and SHA-256 are recorded in runtime-manifest.json.

Alpine source recipes: https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.24-stable
Alpine original source archives: https://distfiles.alpinelinux.org/distfiles/v3.24/
Termux build system: https://github.com/termux/termux-packages/tree/3383e9f8a5566e4445eae6eeeaded8873a0c5f98

The PRoot executable is run as a separate process. No Operit / OperitTerminalCore Kotlin implementation or terminal SDK is included.
