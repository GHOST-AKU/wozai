package dev.ghost.nearbyim;

import dev.ghost.nearbyim.core.AttachmentInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Filesystem component names for the public gallery on Android API 26-28. */
final class GalleryExportNames {
    private GalleryExportNames() { }

    static String legacyFilename(String original, String key) throws IOException {
        String safe = AttachmentInfo.safeName(original);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(safe)) throw new IOException("Invalid gallery filename Unicode");
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IOException("Invalid gallery export key");
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        String suffix = "-" + key.substring(0, 24) + extension;
        int budget = 255 - suffix.getBytes(StandardCharsets.UTF_8).length;
        if (budget < 1) throw new IOException("Gallery filename extension exceeds the filesystem limit");

        // Preserve valid existing paths for crash recovery, without splitting surrogate pairs.
        int end = 0, bytes = 0;
        while (end < stem.length()) {
            int point = stem.codePointAt(end), units = Character.charCount(point);
            int width = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (end + units > 100 || bytes + width > budget) break;
            bytes += width; end += units;
        }
        String prefix = end == 0 ? "attachment".substring(0, Math.min(budget, 10)) : stem.substring(0, end);
        return prefix + suffix;
    }
}
