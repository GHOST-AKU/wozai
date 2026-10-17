package dev.ghost.wozai;

import java.nio.file.*;
import java.util.*;

/** Register in a real child process: AppImage supplies APPDIR/APPIMAGE at process startup. */
public final class AppImageDesktopTests {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--register")) {
            DesktopPlatform.installDesktopEntry(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        Path fixture = Files.createTempDirectory("nearbyim-appimage-desktop-");
        try {
            Path appdir = fixture.resolve("temporary mount/AppDir"), install = appdir.resolve("usr");
            Path launcher = install.resolve("bin/NearbyIM");
            Files.createDirectories(launcher.getParent());
            Files.writeString(launcher, "#!/bin/sh\n"); launcher.toFile().setExecutable(true, true);
            byte[] image = {1, 2, 3, 4};
            Path icon = install.resolve("share/icons/hicolor/256x256/apps/nearbyim.png");
            Files.createDirectories(icon.getParent()); Files.write(icon, image);
            Path artifact = fixture.resolve("NearbyIM O'Brien $HOME %f \"中文\" \\ release.AppImage");
            Files.writeString(artifact, "#!/bin/sh\n"); artifact.toFile().setExecutable(true, true);
            Path data = fixture.resolve("user-data");
            register(install, data, appdir, artifact);
            String text = Files.readString(data.resolve("applications/nearbyim.desktop"));
            check(!text.contains(appdir.toString()), "Desktop launcher still points into the temporary AppImage mount");
            check(text.contains("release.AppImage") && text.contains("%%f"), "Desktop launcher lost the original artifact or its escaping");
            Path savedIcon = data.resolve("icons/hicolor/256x256/apps/nearbyim.png");
            check(Files.isRegularFile(savedIcon) && Arrays.equals(Files.readAllBytes(savedIcon), image), "AppImage menu icon disappears after unmount");
            check(text.contains("Icon=" + savedIcon), "Menu entry does not use the persistent icon");
            register(install, fixture.resolve("normal-package-data"), fixture.resolve("unrelated"), artifact);
            check(Files.readString(fixture.resolve("normal-package-data/applications/nearbyim.desktop")).contains("bin/NearbyIM"), "Unrelated AppImage environment changed a normal package launcher");
            check(!Files.exists(install.resolve("data")), "AppImage wrote user data beside its mounted program");
            System.out.println("AppImage desktop: original artifact, escaped path, persistent icon, unrelated environment and user data checks passed");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void register(Path install, Path data, Path appdir, Path artifact) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), AppImageDesktopTests.class.getName(), "--register", install.toString(), data.toString());
        builder.environment().put("APPDIR", appdir.toString()); builder.environment().put("APPIMAGE", artifact.toString());
        builder.redirectErrorStream(true); Process child = builder.start(); String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(child.waitFor() == 0, "Registration failed: " + output);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
