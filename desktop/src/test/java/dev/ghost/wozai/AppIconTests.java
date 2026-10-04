package dev.ghost.wozai;

import java.awt.Image;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Verify native dimensions, transparency and rejection of damaged ICO frames. */
public final class AppIconTests {
    private static int checks;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); ++checks; }
    private static void rejects(byte[] bytes) throws IOException {
        try { AppIcons.decodeIco(bytes); throw new AssertionError("Accepted a damaged ICO"); }
        catch (IOException expected) { ++checks; }
    }
    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Path.of("assets/icons/windows/nearbyim.ico"));
        List<Image> frames = AppIcons.decodeIco(original);
        check(frames.stream().map(i -> i.getWidth(null)).toList().equals(List.of(16, 24, 32, 48, 64, 128, 256)), "ICO native sizes were lost");
        for (Image image : frames) {
            var frame = (java.awt.image.BufferedImage) image;
            check(frame.getHeight() == frame.getWidth() && frame.getColorModel().hasAlpha(), "ICO frame lost size or transparency");
        }
        List<Image> installed = AppIcons.load();
        check(installed.stream().map(i -> i.getWidth(null)).toList().equals(DesktopIdentity.windows()
            ? List.of(16, 24, 32, 48, 64, 128, 256) : List.of(16, 22, 24, 32, 48, 64, 96, 128, 256, 512)), "Packaged platform icon resources missing");
        check(AppIcons.load() == installed, "Icon images were decoded repeatedly");
        rejects(new byte[5]); rejects(Arrays.copyOf(original, 20));
        byte[] bad = original.clone(); bad[2] = 2; rejects(bad);
        bad = original.clone(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(18, Integer.MAX_VALUE); rejects(bad);
        bad = original.clone(); bad[6] = 17; rejects(bad);
        bad = original.clone(); int offset = ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).getInt(18); bad[offset] = 0; rejects(bad);
        System.out.println("AppIconTests: " + checks + " checks passed (formal native sizes, alpha, packaged resources and corrupt ICO bounds)");
    }
}
