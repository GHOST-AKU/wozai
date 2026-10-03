package dev.ghost.wozai;

import java.awt.*;
import java.util.Locale;
import java.awt.font.FontRenderContext;
import java.awt.geom.PathIterator;
import javax.swing.*;

/** Physical font weights must survive the exact deriveFont path used by headings. */
public final class FontTests {
    public static void main(String[] args) throws Exception {
        Font bundledBold;
        try (var input = FontTests.class.getResourceAsStream("fonts/NotoSansCJKsc-Bold.otf")) {
            if (input == null) throw new AssertionError("Physical Bold is missing from the package");
            bundledBold = Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(14f);
        }
        SwingUtilities.invokeAndWait(() -> {
            for (boolean dark : new boolean[]{false, true, false}) {
                AppTheme.install(dark);
                Font regular = new JLabel().getFont();
                if (!regular.getFontName(Locale.ENGLISH).equals("Noto Sans CJK SC")) throw new AssertionError("Regular UI font missing: " + regular);
                Font bold = regular.deriveFont(Font.BOLD, 14f);
                if (!bold.getFontName(Locale.ENGLISH).equals("Noto Sans CJK SC Bold")) throw new AssertionError("UI uses synthetic bold: " + bold.getFontName(Locale.ENGLISH));
                var frc = new FontRenderContext(null, true, false);
                if (!outline(bold, frc).equals(outline(bundledBold, frc))) throw new AssertionError("UI bold glyphs differ from the physical bundled weight");
                if (regular.canDisplayUpTo("NearbyIM 中文 繁體 日本語 한국어") != -1 || bold.canDisplayUpTo("NearbyIM 中文 繁體 日本語 한국어") != -1) throw new AssertionError("Missing UI glyphs");
            }
        });
        System.out.println("FontTests: physical Regular/Bold, five-language glyphs and theme reinstall passed");
    }
    private static String outline(Font font, FontRenderContext context) {
        PathIterator path = font.createGlyphVector(context, "NearbyIM 中文").getOutline().getPathIterator(null);
        StringBuilder result = new StringBuilder(); float[] coordinates = new float[6];
        while (!path.isDone()) {
            int type = path.currentSegment(coordinates); result.append(type);
            int count = switch (type) { case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2; case PathIterator.SEG_QUADTO -> 4; case PathIterator.SEG_CUBICTO -> 6; default -> 0; };
            for (int i = 0; i < count; i++) result.append(':').append(coordinates[i]);
            path.next();
        }
        return result.toString();
    }
}
