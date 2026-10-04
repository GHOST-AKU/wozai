package dev.ghost.wozai;

import java.awt.Image;
import java.io.*;
import java.nio.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Window icons use the original platform exports, including every native size. */
final class AppIcons {
    private static List<Image> cached;
    static synchronized List<Image> load() throws IOException {
        if (cached != null) return cached;
        List<Image> images = new ArrayList<>();
        if (DesktopIdentity.windows()) {
            try (InputStream input = resource("app-icons/nearbyim.ico")) { images.addAll(decodeIco(input.readAllBytes())); }
        } else {
            for (int size : new int[]{16, 22, 24, 32, 48, 64, 96, 128, 256, 512}) {
                try (InputStream input = resource("app-icons/linux-" + size + ".png")) {
                    var image = ImageIO.read(input);
                    if (image == null || image.getWidth() != size || image.getHeight() != size) throw new IOException("Invalid app icon size: " + size);
                    images.add(image);
                }
            }
        }
        cached = List.copyOf(images); return cached;
    }
    private static InputStream resource(String name) throws IOException {
        InputStream input = AppIcons.class.getResourceAsStream(name);
        if (input == null) throw new IOException("Missing app icon: " + name);
        return input;
    }
    // The supplied ICO contains PNG frames. ImageIO does not read an ICO
    // container itself; decode those original frames without resizing them.
    static List<Image> decodeIco(byte[] bytes) throws IOException {
        if (bytes.length < 6) throw new IOException("Truncated app ICO");
        ByteBuffer directory = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (directory.getShort() != 0 || directory.getShort() != 1) throw new IOException("Invalid app ICO header");
        int count = Short.toUnsignedInt(directory.getShort());
        int header = 6 + count * 16;
        if (count == 0 || header > bytes.length) throw new IOException("Invalid app ICO directory");
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            directory.position(6 + i * 16);
            int width = Byte.toUnsignedInt(directory.get()), height = Byte.toUnsignedInt(directory.get());
            if (width == 0) width = 256; if (height == 0) height = 256;
            directory.position(directory.position() + 6);
            int length = directory.getInt(), offset = directory.getInt();
            if (length < 8 || offset < header || offset > bytes.length - length) throw new IOException("Invalid app ICO frame bounds");
            if (ByteBuffer.wrap(bytes, offset, 8).getLong() != 0x89504e470d0a1a0aL) throw new IOException("Unsupported app ICO frame");
            var image = ImageIO.read(new ByteArrayInputStream(bytes, offset, length));
            if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IOException("Invalid app ICO frame dimensions");
            images.add(image);
        }
        return List.copyOf(images);
    }
}
