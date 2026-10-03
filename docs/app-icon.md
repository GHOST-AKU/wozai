# NearbyIM app icon assets

The supplied source is `app_icon_pack_android_windows_linux.zip`, SHA-256 `9d5a248af4cbd028c39682457e3aabd80ad5963e19f628ff41dbcb27d50f7e27`. [source-pack.json](../desktop/assets/icons/source-pack.json) records the original archive entry, repository path and SHA-256 for all 41 consumed exports. Android launcher images/XML, Windows ICO and Linux hicolor images already matched the supplied pack; comparing the actual archive confirmed their provenance. The Android white background now matches the original XML byte-for-byte too.

The formal pack contains raster PNG exports for Android and Linux, and a multi-resolution ICO for Windows. There is no SVG in this pack. These are the platform-specific original exports; builds do not redesign the symbol or regenerate them from the master.

## Android

`app/src/main/res/mipmap-*` contains legacy launcher PNGs at mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi. Android 8+ uses `mipmap-anydpi-v26` adaptive icon XML with the supplied foreground and white background; Android 13+ adds the supplied monochrome layer in `mipmap-anydpi-v33`. The manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`. The Play Store image is separate at `app/store/play-store-icon-512.png`.

The unused `drawable-nodpi/ic_launcher_artwork.png` master copy was removed. Desktop builds now consume their own platform exports, so the Android debug APK need not include a 1024px desktop master. The original upload and cleaned master remain in `desktop/assets/icons/master/`.

## Windows

`desktop/assets/icons/windows/nearbyim.ico` is the original `windows/app.ico`, renamed without changing its bytes. Its 16, 24, 32, 48, 64, 128 and 256px frames are used by both `jpackage` for the EXE and the running window. Java ImageIO does not support the ICO container, so `AppIcons` decodes its embedded PNG frames directly and passes every original size to `Window.setIconImages`; it performs no resizing. This lets Windows select a suitable frame for its title bar, taskbar and DPI. The 256/512px PNG exports remain available for tools that require them.

## Linux

`desktop/assets/icons/linux/hicolor/<size>/apps/nearbyim.png` contains the supplied 16, 22, 24, 32, 48, 64, 96, 128, 256 and 512px variants. The archive's temporary `papercup-phone` name becomes the stable product name `nearbyim`; image bytes are unchanged. The window uses all ten native sizes. `jpackage` uses the original 256px export; tar.gz and deb include the complete hicolor layout, and deb installs it in `/usr/share/icons/hicolor`. Portable menu entries reference the corresponding image inside the package.

## Verification and toolbar exports

`python3 tools/check-app-icons.py` verifies every original export's recorded hash. To also compare the source archive, pass its path as the first argument. Android preBuild and both desktop build scripts run this check. Desktop tests verify frame sizes, transparency, packaged resources, corrupt ICO bounds and the actual window's icon list. Windows/Linux CI checks the packaged GUI and scaling; no claim is made about every desktop environment's shell icon cache.

`python3 desktop/tools/export-icons.py` regenerates only Material toolbar icons from Android vector assets and requires CairoSVG. It does not overwrite the supplied application ICO or any platform launcher export.
