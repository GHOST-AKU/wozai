package dev.ghost.wozai;

import java.awt.*;
import java.awt.image.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Native-resolution screen evidence, plus an opaque RGB LCD positive control. */
public final class TextRenderingTests {
    private static final String TEXT = "Windows 文字渲染 中文 Aa123";
    private record Fixture(JFrame window, List<JComponent> controls) { }
    private static JComponent control(int type) {
        JComponent view = switch (type) {
            case 0 -> new JLabel(TEXT);
            case 1 -> { JTextArea text = new JTextArea(TEXT + "\n使用说明：认可后开始聊天。"); text.setOpaque(false); text.setEditable(false); yield text; }
            case 2 -> new JButton(TEXT);
            default -> new JTextField(TEXT);
        };
        view.setFont(UIManager.getFont("Label.font").deriveFont(18f));
        view.setForeground(Color.BLACK); view.setBackground(Color.WHITE);
        view.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        view.setFocusable(false);
        if (view instanceof JButton button) { button.setBorderPainted(false); button.setRolloverEnabled(false); }
        view.setPreferredSize(new Dimension(440, 84));
        return view;
    }
    private static int colored(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) {
            int rgb = image.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            if (Math.min(r, Math.min(g, b)) < 220 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 8) ++count;
        }
        return count;
    }
    private static int smoothGray(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) {
            int rgb = image.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            if (r > 8 && r < 240 && r == g && g == b) ++count;
        }
        return count;
    }
    private static BufferedImage rgbPaint(JComponent component, Object hint) {
        component.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, hint);
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        component.paint(g); g.dispose(); return image;
    }
    private static BufferedImage nativeCapture(Robot robot, Component component) {
        Rectangle bounds = new Rectangle(component.getLocationOnScreen(), component.getSize());
        Image image = robot.createMultiResolutionScreenCapture(bounds).getResolutionVariants().stream()
            .max(Comparator.comparingLong(i -> (long)i.getWidth(null) * i.getHeight(null))).orElseThrow();
        BufferedImage result = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics(); g.drawImage(image, 0, 0, null); g.dispose(); return result;
    }
    public static void main(String[] args) throws Exception {
        boolean selfTest = Arrays.asList(args).contains("--self-test");
        if (!DesktopIdentity.windows() && !selfTest) throw new IllegalStateException("Use Windows or --self-test to validate the pixel measurement fixture");
        Path prefix = Path.of(args[0]); Files.createDirectories(prefix.toAbsolutePath().getParent());
        Robot robot = new Robot(); StringBuilder evidence = new StringBuilder();
        for (boolean dark : new boolean[]{false, true, false}) {
            AtomicReference<Fixture> reference = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                AppTheme.install(dark);
                JFrame window = new JFrame(); window.setUndecorated(true);
                JPanel panel = new JPanel(new GridLayout(4, 1)); panel.setBackground(Color.WHITE);
                List<JComponent> controls = new ArrayList<>();
                for (int type = 0; type < 4; ++type) { JComponent view = control(type); panel.add(view); controls.add(view); }
                window.setContentPane(panel); window.pack(); window.setLocation(80, 80); window.setVisible(true);
                SwingUtilities.updateComponentTreeUI(window);
                // A second theme installation also exercises rebuilding already-open controls.
                AppTheme.install(dark); SwingUtilities.updateComponentTreeUI(window);
                if (selfTest) for (JComponent view : controls) view.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                reference.set(new Fixture(window, controls));
            });
            Fixture fixture = reference.get();
            try {
                robot.waitForIdle(); robot.delay(200);
                for (int index = 0; index < fixture.controls.size(); ++index) {
                    JComponent view = fixture.controls.get(index);
                    String label = (dark ? "dark" : "light") + "-" + view.getClass().getSimpleName();
                    BufferedImage nativeImage = nativeCapture(robot, view);
                    ImageIO.write(nativeImage, "png", Path.of(prefix + "-" + label + "-native.png").toFile());
                    Object actualHint = view.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
                    AtomicReference<BufferedImage> positive = new AtomicReference<>();
                    SwingUtilities.invokeAndWait(() -> { positive.set(rgbPaint(view, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)); view.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, actualHint); });
                    ImageIO.write(positive.get(), "png", Path.of(prefix + "-" + label + "-lcd-rgb.png").toFile());
                    String line = label + ": hint=" + actualHint + ", native=" + nativeImage.getWidth() + "x" + nativeImage.getHeight()
                        + ", colored=" + colored(nativeImage) + ", smoothGray=" + smoothGray(nativeImage) + ", lcdPositiveColored=" + colored(positive.get());
                    evidence.append(line).append('\n'); System.out.println(line);
                    Files.writeString(Path.of(prefix + "-evidence.txt"), evidence.toString());
                    if (colored(positive.get()) < 10) throw new AssertionError("LCD positive control did not expose color edges: " + label);
                    if (!RenderingHints.VALUE_TEXT_ANTIALIAS_ON.equals(actualHint)) throw new AssertionError("Actual Swing control uses LCD/system text AA: " + label);
                    if (colored(nativeImage) != 0 || smoothGray(nativeImage) < 20) throw new AssertionError("Native text has color fringes or grayscale smoothing is absent: " + label);
                }
                SwingUtilities.invokeAndWait(() -> { try { TextRenderingDiagnostics.write(fixture.window, Path.of(prefix + "-" + (dark ? "dark" : "light") + "-diagnostics.txt"), 1f); } catch (Exception e) { throw new RuntimeException(e); } });
            } finally { SwingUtilities.invokeAndWait(fixture.window::dispose); }
        }
        System.out.println("TextRenderingTests: native grayscale pixels, opaque RGB LCD positive controls and rebuilt light/dark controls passed");
    }
}
