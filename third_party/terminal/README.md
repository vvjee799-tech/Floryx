# Bundled command runtime

FlowChat's Kotlin integration is implemented in this repository. Operit / OperitTerminalCore were architectural references only; their Kotlin source and AAR are not included.

The following independent components are distributed with the APK:

| Component | Version | Upstream licensing |
| --- | --- | --- |
| Termux PRoot and its loaders | 5.1.107.98 | GPL; see upstream COPYING and source headers |
| talloc shared library | 2.5.0 | LGPL-3.0-or-later; source archive LICENSE. Termux's package recipe labels the package GPL-3.0. |
| libandroid-shmem | 0.7 | BSD-3-Clause |
| Alpine Linux minirootfs | 3.24.2, aarch64 / x86_64 | Distribution of separately licensed packages; package metadata retained in the root filesystem |
| Apache Commons Compress | 1.28.0 | Apache-2.0 |

Full license texts and notices are packaged in `assets/terminal/licenses/` and `assets/terminal/NOTICE.md` inside the APK. This does not replace the individual upstream license terms.

## Corresponding native sources

`app/src/main/assets/terminal/native-sources.zip` is shipped **inside the APK**. It contains the checksum-verified PRoot, talloc and libandroid-shmem upstream source archives, the applicable Termux build recipes/helper, and full license texts. The executables/shared libraries have been renamed for Android native-library packaging, with no binary patches. FlowChat supplies the runtime paths through process arguments/environment and dynamically locates talloc through a symlink.

`runtime-manifest.json` identifies each original `.deb`, rootfs URL, version and SHA-256, the source-bundle checksum, and Termux recipe commit `3383e9f8a5566e4445eae6eeeaded8873a0c5f98`. Recipes are also retained under this directory for review. To build native components, use the official [Termux package build system at this commit](https://github.com/termux/termux-packages/tree/3383e9f8a5566e4445eae6eeeaded8873a0c5f98) and its documented Docker/NDK setup, then build `proot`, `libtalloc` and `libandroid-shmem` for the desired architecture. Preserve the separate PRoot loader files and ELF page alignment.

Upstream source archives and recorded SHA-256:

- [PRoot v5.1.107.98](https://github.com/termux/proot/archive/v5.1.107.98.zip): `9de61bddd6f5016d065e2755b72ff4747290e5f992562f031785b107b51d02ba`.
- [talloc 2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz): `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`.
- [libandroid-shmem v0.7](https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz): `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`.

## Alpine distribution

The two minirootfs archives are unmodified official Alpine releases. For installed package versions, licenses, origins and source checksums, inspect `/lib/apk/db/installed` or `/var/lib/apk/db/installed` in the extracted distribution (APK database format depends on the bundled package-manager version). The APK includes an `alpine-packages.json` inventory for each bundled archive.

Corresponding distribution recipes are available from [Alpine aports, 3.24-stable](https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.24-stable), and original package source archives from the official [Alpine v3.24 source archive](https://distfiles.alpinelinux.org/distfiles/v3.24/). Use the exact package version/origin and APKBUILD source/checksum entries when retrieving source. User-installed packages remain managed by Alpine's package manager.

## Updating

`tools/prepare_terminal_runtime.py` restores the existing manifest's pinned binary/rootfs files and validates SHA-256; it does not discover new versions. A runtime update must explicitly update binaries, rootfs metadata, licenses, corresponding source bundle and verification together. Keep downloaded archives in `D:/download/FlowChat-runtime` on the user's Windows machine. Installed Linux files are intentionally preserved across application updates.
