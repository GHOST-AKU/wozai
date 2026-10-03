# NearbyIM app icon assets

The shared source artwork is `desktop/assets/icons/master/icon-master-1024.png`. The untouched supplied image is kept beside it as `original-upload.png`. Platform exports are stored with the platform that consumes them; Android runtime resources are installed directly in `app/src/main/res/`.

## Android

`app/src/main/res/mipmap-*` contains legacy launcher PNGs at mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi. The `mipmap-anydpi-v26` adaptive icons use the supplied foreground layers and white background. The `mipmap-anydpi-v33` variants add the monochrome layer for Android themed icons. Both launcher names remain `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, as referenced by the manifest.

The Play Store listing image is separate from runtime resources at `app/store/play-store-icon-512.png`. `drawable-nodpi/ic_launcher_artwork.png` is a copy of the shared master used by the desktop window icon as well.

## Windows

`desktop/assets/icons/windows/nearbyim.ico` contains 16, 24, 32, 48, 64, 128 and 256 pixel sizes and is passed to `jpackage` by `desktop/tools/build.ps1`. The 256 and 512 pixel PNG exports are kept beside it for packaging tools that require PNG input.

## Linux

`desktop/assets/icons/linux/hicolor/<size>/apps/nearbyim.png` follows the freedesktop hicolor icon theme layout and includes 16, 22, 24, 32, 48, 64, 96, 128, 256 and 512 pixel variants. The supplied pack used the temporary name `papercup-phone`; repository copies use the product's stable `nearbyim` name. Linux app packaging is not yet part of this project, so these files are ready for a future launcher/package integration.

## Regenerating desktop resources

Run `python3 desktop/tools/export-icons.py` from the repository root after changing the shared master or Material vector assets. This developer-only utility requires Pillow and CairoSVG; packaged builds use checked-in assets.
