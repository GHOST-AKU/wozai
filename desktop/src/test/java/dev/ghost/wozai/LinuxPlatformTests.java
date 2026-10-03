package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.LocalizedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Linux locations must preserve profiles without relying on install or working directories. */
public final class LinuxPlatformTests {
    private static int passed;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nearbyim-linux-platform-");
        try {
            Map<String, String> properties = Map.of("user.home", root.resolve("home").toString(),
                    "wozai.installDir", "/opt/NearbyIM");
            Map<String, String> environment = Map.of("XDG_DATA_HOME", root.resolve("用户数据").toString());
            var location = DataLocation.select(properties::get, environment::get, false);
            check(location.path().equals(root.resolve("用户数据/wozai")), "Absolute XDG_DATA_HOME was ignored");
            check(!location.portable() && location.legacyPath() == null, "Linux writes beside installed binaries");
            var relative = DataLocation.select(properties::get, Map.of("XDG_DATA_HOME", "relative/path")::get, false);
            check(relative.path().equals(root.resolve("home/.local/share/wozai")), "Relative XDG path did not use the standard default");
            var fallback = DataLocation.select(properties::get, key -> null, false);
            check(fallback.path().equals(relative.path()), "Existing Linux development profile moved");
            var xdgOnly = DataLocation.select(key -> null, environment::get, false);
            check(xdgOnly.path().equals(location.path()), "Valid XDG requires an unrelated home property");
            var override = DataLocation.select(Map.of("wozai.dataDir", root.resolve("explicit").toString())::get,
                    key -> { throw new AssertionError("Explicit path consulted XDG"); }, false);
            check(override.path().equals(root.resolve("explicit")), "Explicit path lost precedence");
            try (var prepared = location.prepare()) {
                var identity = DesktopIdentity.load(prepared.path().resolve("identity.properties"));
                try (var store = new DesktopStore(prepared.path())) {
                    store.setSetting("language", "ja");
                    store.setSetting("nickname", "Linux 朋友");
                }
                check(Files.getPosixFilePermissions(prepared.path()).equals(PosixFilePermissions.fromString("rwx------")), "Data directory permits other users");
                check(Files.getPosixFilePermissions(prepared.path().resolve("identity.properties")).equals(PosixFilePermissions.fromString("rw-------")), "Private key permits other users");
                var restored = DesktopIdentity.load(prepared.path().resolve("identity.properties"));
                check(restored.id().equals(identity.id()) && restored.signer().publicKey().equals(identity.signer().publicKey()), "Linux restart changed identity");
            }
            try (var prepared = location.prepare(); var store = new DesktopStore(prepared.path())) {
                check(store.language().equals("ja") && store.nickname().equals("Linux 朋友"), "Linux restart lost saved settings");
            }
            Path identityPath = location.path().resolve("identity.properties");
            Files.writeString(identityPath, "broken identity");
            try { DesktopIdentity.load(identityPath); throw new AssertionError("Corrupt Linux identity was regenerated"); }
            catch (LocalizedIOException expected) { check(Files.readString(identityPath).equals("broken identity"), "Failure changed private data"); }
            Path install = root.resolve("NearbyIM O'Brien $HOME %f \"quote\" \\ folder");
            Files.createDirectories(install.resolve("bin"));
            Path launcher = Files.writeString(install.resolve("bin/NearbyIM"), "#!/bin/sh\n");
            launcher.toFile().setExecutable(true, true);
            Files.createDirectories(install.resolve("share/icons/hicolor/256x256/apps"));
            Files.write(install.resolve("share/icons/hicolor/256x256/apps/nearbyim.png"), new byte[]{1});
            Path entry = DesktopPlatform.installDesktopEntry(install, root.resolve("desktop-data"));
            String entryText = Files.readString(entry);
            check(entry.getParent().equals(root.resolve("desktop-data/applications")), "Desktop entry ignored XDG location");
            check(entryText.contains("Terminal=false") && entryText.contains("Categories=Network;InstantMessaging;"), "Desktop integration metadata missing");
            check(entryText.contains("Exec=/usr/bin/env -- \"") && entryText.contains("TryExec="), "GIO cannot validate percent-containing executable paths");
            check(entryText.contains("%%f") && entryText.contains("\\\\$") && entryText.contains("\\\\\""), "Desktop Exec did not escape metacharacters");
            check(entryText.contains("Name[zh_CN]=") && entryText.contains("Name[ja]="), "Desktop entry lost localized names");
            check(!Files.exists(install.resolve("data")), "Desktop installation created data in program folder");
            for (String language : List.of("zh-Hans", "en", "zh-Hant", "ja", "ko")) {
                var strings = new Strings(language);
                check(strings.text("versionLinux", "0.3.1").contains("Linux"), "Linux version not translated: " + language);
                check(!strings.text("helpBodyLinux").contains("Windows"), "Windows instructions on Linux: " + language);
                check(!strings.text("aboutBodyLinux", "0.3.1").contains("Windows"), "Windows about text on Linux: " + language);
                check(strings.text("bluetoothHintLinux").contains("BlueZ"), "Bluetooth platform instructions missing: " + language);
            }
            System.out.println("LinuxPlatformTests: " + passed + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
