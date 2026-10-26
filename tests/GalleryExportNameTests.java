package dev.ghost.nearbyim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Legacy gallery names must fit the complete filesystem component, not only its stem. */
public final class GalleryExportNameTests {
    private static final String KEY = "a".repeat(64);
    private static final String SUFFIX = "-" + "a".repeat(24) + ".png";

    public static void main(String[] args) throws Exception {
        cjkByteBudget();
        surrogateBoundary();
        existingValidNamesStayStable();
        extensionAndKeyBudget();
        sanitizedNames();
        actualFilesystemNames();
        System.out.println("GalleryExportNameTests: UTF-8 component budget, complete code points, stable suffixes, sanitization and real file creation passed");
    }

    private static void cjkByteBudget() throws Exception {
        String name = GalleryExportNames.legacyFilename("中".repeat(80) + ".png", KEY);
        check(name.getBytes(StandardCharsets.UTF_8).length <= 255, "Gallery filename exceeds 255 UTF-8 bytes after appending its key and extension");
        check(name.equals("中".repeat(75) + SUFFIX), "Gallery filename did not reserve all suffix bytes before truncating its stem");
    }

    private static void surrogateBoundary() throws Exception {
        String name = GalleryExportNames.legacyFilename("a".repeat(99) + "😀.png", KEY);
        check(StandardCharsets.UTF_8.newEncoder().canEncode(name), "A surrogate pair was split at the old 100-unit boundary");
        check(name.equals("a".repeat(99) + SUFFIX), "Gallery prefix changed an existing whole code point");
        String emoji = GalleryExportNames.legacyFilename("😀".repeat(80) + ".png", KEY);
        check(emoji.equals("😀".repeat(50) + SUFFIX), "Whole supplementary characters did not respect the legacy stem budget");
    }

    private static void existingValidNamesStayStable() throws Exception {
        check(GalleryExportNames.legacyFilename("holiday.png", KEY).equals("holiday" + SUFFIX), "Existing short gallery names changed");
        check(GalleryExportNames.legacyFilename("a".repeat(120) + ".png", KEY).equals("a".repeat(100) + SUFFIX), "Existing valid legacy names changed and could duplicate a crash-recovered export");
        check(GalleryExportNames.legacyFilename("原件.png", KEY).equals("原件" + SUFFIX), "An already valid Unicode filename changed");
    }

    private static void extensionAndKeyBudget() throws Exception {
        String extension = "." + "界".repeat(75);
        String name = GalleryExportNames.legacyFilename("😀😀" + extension, KEY);
        check(name.equals("😀-" + "a".repeat(24) + extension), "A long extension was not included in the UTF-8 budget");
        check(name.getBytes(StandardCharsets.UTF_8).length == 255, "Exact 255-byte component was rejected or miscounted");
        String narrow = GalleryExportNames.legacyFilename("中." + "界".repeat(76), KEY);
        check(narrow.equals("a-" + "a".repeat(24) + "." + "界".repeat(76)), "A one-byte remaining budget did not produce a bounded nonempty stem");
        try {
            GalleryExportNames.legacyFilename("x." + "界".repeat(100), KEY);
            throw new AssertionError("An extension larger than the component budget was accepted");
        } catch (IOException expected) { }
        String other = GalleryExportNames.legacyFilename("中".repeat(80) + ".png", "b".repeat(64));
        check(!other.equals(GalleryExportNames.legacyFilename("中".repeat(80) + ".png", KEY)), "Truncation discarded the per-export identifier");
    }

    private static void sanitizedNames() throws Exception {
        for (String input : List.of("../../照片.png", "..", "CON.png", "image\u202egnp.png")) {
            String name = GalleryExportNames.legacyFilename(input, KEY);
            check(!name.contains("/") && !name.contains("\\") && !name.contains("\u202e"), "Gallery filename escaped sanitization");
            check(name.getBytes(StandardCharsets.UTF_8).length <= 255, "Sanitized filename exceeded its component budget");
        }
        for (String invalid : List.of("broken\ud83d.png", "broken\ude00.png")) {
            try { GalleryExportNames.legacyFilename(invalid, KEY); throw new AssertionError("Malformed Unicode was silently replaced"); }
            catch (IOException expected) { }
        }
    }

    private static void actualFilesystemNames() throws Exception {
        Path directory = Files.createTempDirectory("gallery-export-names-");
        byte[] original = {0, 13, 17, (byte)255};
        try {
            for (String name : List.of("中".repeat(80) + ".png", "a".repeat(99) + "😀.png", "😀".repeat(80) + ".png", "😀😀." + "界".repeat(75))) {
                Path target = directory.resolve(GalleryExportNames.legacyFilename(name, KEY));
                Files.write(target, original, StandardOpenOption.CREATE_NEW);
                check(Arrays.equals(Files.readAllBytes(target), original), "Creating a Unicode export filename changed its file contents");
            }
        } finally {
            try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(directory);
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
