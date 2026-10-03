package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.LanguageRegistry;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** One atomic file per message. History and trust have separate lifetimes. */
public final class DesktopStore implements AutoCloseable {
    public record Peer(String id, String name, String publicKey, String endpoint) {
        public Peer { id = uuid(id); }
        public String toString() { return name + (publicKey.isEmpty() ? "" : " ✓"); }
    }
    public record Message(String id, String body, long time, boolean outgoing, String status, long senderTime) {
        public Message { id = uuid(id); }
        public Message(String id, String body, long time, boolean outgoing, String status) {
            this(id, body, time, outgoing, status, time);
        }
    }
    private final Path root;
    private final boolean existingIdentity;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public DesktopStore(Path root) throws IOException {
        this.root = root; Files.createDirectories(root); AtomicFiles.privatePermissions(root, true);
        existingIdentity = Files.exists(root.resolve("identity.properties"), LinkOption.NOFOLLOW_LINKS);
        lockChannel = FileChannel.open(root.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { lockChannel.close(); throw new LocalizedIOException(UiText.of("dataInUse", root.toString()), e); }
        if (acquired == null) { lockChannel.close(); throw new LocalizedIOException(UiText.of("dataInUse", root.toString())); }
        lock = acquired;
        try {
            for (Peer peer : peers()) unknown(peer.id());
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    static String uuid(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
        return id.toLowerCase(Locale.ROOT);
    }
    private Path peerFile(String id) { return root.resolve("peers").resolve(uuid(id) + ".properties"); }
    private Path messagesPath(String peer) { return root.resolve("messages").resolve(uuid(peer)); }
    public synchronized Peer peer(String id) throws IOException {
        Path path = peerFile(id); if (!Files.exists(path)) return null;
        Properties v = AtomicFiles.read(path);
        return new Peer(id, AtomicFiles.required(v, "name"), v.getProperty("key", ""), v.getProperty("endpoint", ""));
    }
    public synchronized void peer(Peer peer) throws IOException {
        Properties v = new Properties(); v.setProperty("name", peer.name()); v.setProperty("key", peer.publicKey()); v.setProperty("endpoint", peer.endpoint());
        AtomicFiles.write(peerFile(peer.id()), v);
    }
    public synchronized List<Peer> peers() throws IOException {
        Path path = root.resolve("peers"); if (!Files.exists(path)) return List.of();
        List<Peer> peers = new ArrayList<>();
        try (var files = Files.list(path)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".properties")).sorted().toList())
                peers.add(peer(file.getFileName().toString().replace(".properties", "")));
        }
        return List.copyOf(peers);
    }
    public synchronized void save(String peer, Message message) throws IOException {
        Path file = messagesPath(peer).resolve(uuid(message.id()) + ".properties");
        if (Files.exists(file)) {
            if (!readMessage(file).equals(message)) throw new IOException("Conflicting message ID");
        } else writeMessage(file, message);
    }
    public synchronized void receive(String peer, String id, String body, long senderTime) throws IOException {
        Path file = messagesPath(peer).resolve(uuid(id) + ".properties");
        // Retries retain the first local receipt time, but must match the wire content.
        long receiptTime = Files.exists(file) ? readMessage(file).time() : System.currentTimeMillis();
        save(peer, new Message(id, body, receiptTime, false, "received", senderTime));
    }
    private void writeMessage(Path file, Message m) throws IOException {
        Properties v = new Properties(); v.setProperty("body", m.body()); v.setProperty("time", Long.toString(m.time()));
        v.setProperty("senderTime", Long.toString(m.senderTime()));
        v.setProperty("outgoing", Boolean.toString(m.outgoing())); v.setProperty("status", m.status()); AtomicFiles.write(file, v);
    }
    private Message readMessage(Path file) throws IOException {
        Properties v = AtomicFiles.read(file);
        try {
            String status = AtomicFiles.required(v, "status"), outgoing = AtomicFiles.required(v, "outgoing");
            if (!Set.of("received", "pending", "delivered", "unknown").contains(status) || !Set.of("true", "false").contains(outgoing)) throw new IOException("Invalid message state");
            long time = Long.parseLong(AtomicFiles.required(v, "time"));
            // Before receipt-time ordering, incoming `time` was the sender timestamp.
            long senderTime = Long.parseLong(v.getProperty("senderTime", Long.toString(time)));
            return new Message(uuid(file.getFileName().toString().replace(".properties", "")), AtomicFiles.required(v, "body"), time, Boolean.parseBoolean(outgoing), status, senderTime);
        } catch (IllegalArgumentException e) { throw new IOException("Corrupt message", e); }
    }
    public synchronized List<Message> messages(String peer) throws IOException {
        Path directory = messagesPath(peer); if (!Files.exists(directory)) return List.of();
        // Keep only the most recent 200 in memory; all other files remain on disk.
        Comparator<Message> order = Comparator.comparingLong(Message::time).thenComparing(Message::id);
        PriorityQueue<Message> latest = new PriorityQueue<>(order);
        try (var paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths.filter(f -> f.toString().endsWith(".properties"))::iterator) {
                latest.add(readMessage(path)); if (latest.size() > 200) latest.remove();
            }
        }
        return latest.stream().sorted(order).toList();
    }
    public synchronized void acknowledge(String peer, String id) throws IOException { status(peer, id, "delivered"); }
    public synchronized void status(String peer, String id, String status) throws IOException {
        Path file = messagesPath(peer).resolve(uuid(id) + ".properties"); if (!Files.exists(file)) return;
        Message m = readMessage(file);
        if (m.outgoing()) writeMessage(file, new Message(m.id(), m.body(), m.time(), true, status, m.senderTime()));
    }
    public synchronized void unknown(String peer) throws IOException {
        Path directory = messagesPath(peer); if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                Message m = readMessage(file);
                if (m.outgoing() && m.status().equals("pending")) writeMessage(file, new Message(m.id(), m.body(), m.time(), true, "unknown", m.senderTime()));
            }
        }
    }
    public synchronized void revoke(String id) throws IOException {
        Peer p = peer(id); if (p != null) peer(new Peer(p.id(), p.name(), "", p.endpoint()));
    }
    public synchronized void clear(String id) throws IOException {
        Path path = messagesPath(id); if (!Files.exists(path)) return;
        try (var files = Files.list(path)) { for (Path file : files.toList()) Files.delete(file); }
    }
    public synchronized String draft(String peer) throws IOException {
        return AtomicFiles.read(root.resolve("drafts").resolve(uuid(peer) + ".properties")).getProperty("text", "");
    }
    public synchronized void draft(String peer, String text) throws IOException {
        Properties v = new Properties(); v.setProperty("text", text);
        AtomicFiles.write(root.resolve("drafts").resolve(uuid(peer) + ".properties"), v);
    }
    public synchronized String setting(String key, String fallback) throws IOException { return AtomicFiles.read(root.resolve("settings.properties")).getProperty(key, fallback); }
    public synchronized String language() throws IOException {
        String saved = setting("language", LanguageRegistry.SYSTEM);
        String normalized = LanguageRegistry.normalizeSelection(saved);
        if (!normalized.equals(saved)) setSetting("language", normalized);
        return normalized;
    }
    public synchronized String nickname() throws IOException {
        String saved = setting("nickname", null);
        if (saved != null) return saved;
        // Earlier profiles advertised this Chinese default without saving it. Keep that
        // identity name on upgrade; only a fresh profile adopts the initial UI language.
        String initial = new Strings(existingIdentity ? "zh-Hans" : language()).text("defaultNicknameWindows");
        setSetting("nickname", initial);
        return initial;
    }
    public synchronized void setSetting(String key, String value) throws IOException {
        if (key.equals("language")) value = LanguageRegistry.normalizeSelection(value);
        Properties v = AtomicFiles.read(root.resolve("settings.properties")); v.setProperty(key, value); AtomicFiles.write(root.resolve("settings.properties"), v);
    }
    public synchronized void close() throws IOException { if (lock.isValid()) lock.release(); lockChannel.close(); }
}
