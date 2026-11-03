# Packaging

Release packages are built by `.github/workflows/release.yml` with `jpackage`:

| OS | Package | Notes |
|---|---|---|
| Windows | `Oba-<version>-windows-x64.exe` installer, `…-windows-x64.zip` portable | per-user install (no admin), Start menu and desktop shortcuts, `.scbackup` association; needs WiX 3 (installed in CI) |
| macOS | `Oba-<version>-macos-arm64.dmg` | icon made from `macos/oba.iconset` with `iconutil` |
| Linux | `oba_<version>_amd64.deb` | menu entry under Network, `.scbackup` association |

The icons are drawn by `src/test/java/com/emirchat/tools/IconMaker.java`; run it to regenerate
every size after changing the design.

The Windows upgrade code below must never change, so new versions replace old ones:

    WIN_UPGRADE_UUID = 3b9f6c1e-6a52-4f0e-9a51-6c0f2c7e5a11