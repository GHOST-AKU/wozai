package dev.ghost.wozai;

import java.io.IOException;
import java.nio.file.*;

/** Platform-specific help and settings without changing the shared chat model. */
final class DesktopPlatform {
    static String key(String stem) { return stem + (DesktopIdentity.windows() ? "Windows" : "Linux"); }
    static Path installDesktopEntry(Path install, Path dataHome) throws IOException {
        Path root = install.toAbsolutePath().normalize();
        Path executable = root.resolve("bin/NearbyIM");
        Path icon = root.resolve("share/icons/hicolor/256x256/apps/nearbyim.png");
        if (!Files.isExecutable(executable) || !Files.isRegularFile(icon)) throw new IOException("This command requires a complete Linux package");
        String command = desktopValue(executable.toString().replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`").replace("%", "%%"));
        StringBuilder text = new StringBuilder("[Desktop Entry]\nType=Application\nName=NearbyIM\n");
        String[][] names = {{"zh_CN", "zh-Hans"}, {"zh_TW", "zh-Hant"}, {"ja", "ja"}, {"ko", "ko"}};
        for (String[] name : names) text.append("Name[").append(name[0]).append("]=").append(new Strings(name[1]).text("app")).append('\n');
        // GIO validates argv[0] before expanding desktop field codes (including
        // %%). An env launcher keeps percent-containing install paths in argv[1].
        text.append("TryExec=").append(desktopValue(executable.toString()))
                .append("\nExec=/usr/bin/env -- \"").append(command).append("\"\nIcon=").append(desktopValue(icon.toString()))
                .append("\nTerminal=false\nCategories=Network;InstantMessaging;\nStartupNotify=true\nStartupWMClass=dev-ghost-wozai-Main\n");
        Path directory = dataHome.toAbsolutePath().normalize().resolve("applications");
        Files.createDirectories(directory);
        Path entry = directory.resolve("nearbyim.desktop"), temporary = Files.createTempFile(directory, ".nearbyim-", ".desktop");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return entry;
    }
    private static String desktopValue(String value) throws IOException {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IOException("Desktop launcher paths cannot contain line breaks");
        return value.replace("\\", "\\\\");
    }
    static void openBluetoothSettings() throws IOException {
        if (DesktopIdentity.windows()) {
            new ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:bluetooth").start();
            return;
        }
        String[][] candidates = {{"blueman-manager"}, {"gnome-control-center", "bluetooth"},
                {"systemsettings", "kcm_bluetooth"}, {"systemsettings5", "kcm_bluetooth"}};
        String path = System.getenv("PATH");
        if (path != null) for (String[] command : candidates) for (String directory : path.split(":")) {
            if (directory.isEmpty()) continue;
            Path binary = Path.of(directory).resolve(command[0]);
            if (Files.isRegularFile(binary) && Files.isExecutable(binary)) {
                String[] args = command.clone(); args[0] = binary.toAbsolutePath().toString();
                new ProcessBuilder(args).start(); return;
            }
        }
        throw new IOException("No Linux Bluetooth settings application is installed");
    }
}
