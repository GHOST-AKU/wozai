package dev.ghost.wozai.next;

import dev.ghost.nearbyim.core.*;
import dev.ghost.wozai.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Test-only artificial data; never packaged in the production classes or launcher. */
public final class BridgeFixtures {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("seed")) throw new IllegalArgumentException("Usage: BridgeFixtures seed EMPTY_PROFILE");
        Path profile = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(profile)) try (var entries = Files.list(profile)) {
            if (entries.findAny().isPresent()) throw new IOException("Fixture seeding requires an empty, independent profile");
        }
        seed(profile);
        System.out.println("Artificial WinUI screenshot fixture seeded: " + profile);
    }

    static void seed(Path profile) throws Exception {
        Files.createDirectories(profile);
        try (DesktopStore store = new DesktopStore(profile)) {
            store.setSetting("language", "zh-Hans"); store.setSetting("nickname", "我的电脑");
            DesktopIdentity.Identity identity = DesktopIdentity.load(profile.resolve("identity.properties"));
            new AvatarStore(profile.resolve("avatars"), identity.signer().publicKey()).save(0, avatar(new Color(52, 113, 228), "W"));
            long now = System.currentTimeMillis();
            String[] names = {"林间 · 设计", "小周的手机", "项目资料"};
            String[] ids = {"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333"};
            for (int i = 0; i < names.length; i++) {
                DeviceIdentity peer = DeviceIdentity.generate();
                store.peer(new DesktopStore.Peer(ids[i], names[i], peer.publicKey(), ""));
                new AvatarStore(profile.resolve("avatars"), peer.publicKey()).save(0, avatar(new Color[]{new Color(82, 132, 111), new Color(218, 151, 70), new Color(133, 113, 181)}[i], new String[]{"L", "Z", "P"}[i]));
            }
            text(store, ids[0], "早上好，整理好的桌面方案发你啦。", now - 24 * 60_000, false, "received");
            text(store, ids[0], "收到。留白和字号都很舒服，我们就按这个方向继续。", now - 23 * 60_000, true, "read");
            text(store, ids[0], "我把配色笔记也放在下面，周一开会时可以一起看。", now - 21 * 60_000, false, "received");
            completeFile(store, ids[0], "桌面设计笔记.txt", "text/plain", "WinUI 3 桌面设计笔记\n\n使用原生控件、清晰留白和可读的字体。\n此文件为内部截图夹具，不含真实用户数据。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), now - 20 * 60_000, false);
            text(store, ids[0], "好的，我会把文件在本机另存一份。👍", now - 18 * 60_000, true, "delivered");
            text(store, ids[0], "没问题。附近连接也可以直接发照片和多个文件。", now - 17 * 60_000, false, "received");
            store.draft(ids[0], "下次我们一起试试照片传输");
            text(store, ids[1], "照片已经传到电脑，原图也保存好了。", now - 75 * 60_000, false, "received");
            text(store, ids[1], "收到，晚点再看。", now - 74 * 60_000, true, "read");
            text(store, ids[2], "这周的资料都在这里，离线也能查看历史记录。", now - 26 * 3_600_000, false, "received");
            text(store, ids[2], "谢谢，周一见。", now - 25 * 3_600_000, true, "delivered");
        }
    }

    static String seedLarge(Path profile) throws Exception {
        Files.createDirectories(profile);
        String peer = "44444444-4444-4444-8444-444444444444";
        try (DesktopStore store = new DesktopStore(profile)) {
            DesktopIdentity.load(profile.resolve("identity.properties"));
            store.peer(new DesktopStore.Peer(peer, "Long history fixture", DeviceIdentity.generate().publicKey(), ""));
            for (int i = 0; i < 100; i++) text(store, peer, i + ":" + "\"\\\t".repeat(8000), 1000 + i, i % 2 == 0, i % 2 == 0 ? "delivered" : "received");
        }
        return peer;
    }

    private static void text(DesktopStore store, String peer, String body, long time, boolean outgoing, String status) throws IOException {
        store.save(peer, new DesktopStore.Message(UUID.randomUUID().toString(), body, time, outgoing, status));
    }

    private static void completeFile(DesktopStore store, String peer, String name, String mime, byte[] data, long time, boolean outgoing) throws Exception {
        AttachmentInfo info = AttachmentInfo.v2(UUID.randomUUID().toString(), name, mime, data.length,
                AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(data)), time);
        Path source = store.attachmentFile(peer, info, outgoing); Files.createDirectories(source.getParent()); Files.write(source, data);
        store.attachment(peer, new AttachmentRecord(info, outgoing, outgoing ? "delivered" : "received", data.length).withRoute("lan"));
    }

    private static byte[] avatar(Color color, String letter) throws IOException {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(color); graphics.fillRoundRect(0, 0, 128, 128, 32, 32);
            graphics.setColor(Color.WHITE); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 62));
            FontMetrics metrics = graphics.getFontMetrics(); graphics.drawString(letter, (128 - metrics.stringWidth(letter)) / 2, 85);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
}
