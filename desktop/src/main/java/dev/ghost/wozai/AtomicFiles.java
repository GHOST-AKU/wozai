package dev.ghost.wozai;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

final class AtomicFiles {
    static Properties read(Path file) throws IOException {
        Properties values = new Properties();
        if (Files.exists(file)) {
            if (Files.size(file) > 65536) throw new IOException("Local file exceeds size limit");
            try (InputStream input = Files.newInputStream(file)) { values.load(input); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid local file", e); }
        }
        return values;
    }
    static void write(Path file, Properties values) throws IOException {
        Files.createDirectories(file.getParent());
        privatePermissions(file.getParent(), true);
        Path temporary = Files.createTempFile(file.getParent(), ".write-", ".tmp");
        try {
            privatePermissions(temporary, false);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); values.store(output, "WoZai");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Same-directory rename: never acknowledge a partly written message.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    static String required(Properties values, String key) throws IOException {
        String result = values.getProperty(key);
        if (result == null) throw new IOException("Missing local field: " + key);
        return result;
    }
}
