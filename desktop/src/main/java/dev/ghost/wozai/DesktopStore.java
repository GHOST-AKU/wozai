package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.LanguageRegistry;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import dev.ghost.nearbyim.core.AttachmentInfo;
import dev.ghost.nearbyim.core.AttachmentRecord;
import dev.ghost.nearbyim.core.AttachmentTransfer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** One atomic file per message. History and trust have separate lifetimes. */
public final class DesktopStore implements AutoCloseable {
    public record Peer(String id, String name, String publicKey, String endpoint) {
        public Peer { id = uuid(id); }
        public String toString() { return name + (publicKey.isEmpty() ? "" : " ✓"); }
    }
    public record Message(String id, String body, long time, boolean outgoing, String status, long senderTime, AttachmentRecord attachment) {
        public Message { id = uuid(id); }
        public Message(String id,String body,long time,boolean outgoing,String status,long senderTime) { this(id,body,time,outgoing,status,senderTime,null); }
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
            for (Peer peer : peers()) { unknown(peer.id()); recoverAttachments(peer.id()); }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    static String uuid(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
        return id.toLowerCase(Locale.ROOT);
    }
    private Path peerFile(String id) { return root.resolve("peers").resolve(uuid(id) + ".properties"); }
    private Path messagesPath(String peer) { return root.resolve("messages").resolve(uuid(peer)); }
    private Path messageFile(String peer, String id, boolean outgoing) throws IOException {
        Path directory = messagesPath(peer);
        String canonical = uuid(id);
        Path file = directory.resolve((outgoing ? "out-" : "in-") + canonical + ".properties");
        if (Files.exists(file)) return file;
        // Legacy files stay in place. Their persisted direction determines their namespace.
        Path legacy = directory.resolve(canonical + ".properties");
        if (Files.exists(legacy) && readMessage(legacy).outgoing() == outgoing) return legacy;
        return file;
    }
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
        Path file = messageFile(peer, message.id(), message.outgoing());
        if (Files.exists(file)) {
            if (!readMessage(file).equals(message)) throw new IOException("Conflicting message ID");
        } else writeMessage(file, message);
    }
    public synchronized void receive(String peer, String id, String body, long senderTime) throws IOException {
        Path file = messageFile(peer, id, false);
        // Retries retain the first local receipt time, but must match the wire content.
        long receiptTime = Files.exists(file) ? readMessage(file).time() : System.currentTimeMillis();
        save(peer, new Message(id, body, receiptTime, false, "received", senderTime));
    }
    private void writeMessage(Path file, Message m) throws IOException {
        Properties v = new Properties(); v.setProperty("body", m.body()); v.setProperty("time", Long.toString(m.time()));
        v.setProperty("senderTime", Long.toString(m.senderTime()));
        if(m.attachment()!=null)v.setProperty("attachment",m.attachment().encode());
        v.setProperty("outgoing", Boolean.toString(m.outgoing())); v.setProperty("status", m.status()); AtomicFiles.write(file, v);
    }
    private Message readMessage(Path file) throws IOException {
        Properties v = AtomicFiles.read(file);
        try {
            String status = AtomicFiles.required(v, "status"), outgoing = AtomicFiles.required(v, "outgoing");
            if (!Set.of("received", "pending", "delivered", "unknown").contains(status) || !Set.of("true", "false").contains(outgoing)) throw new IOException("Invalid message state");
            String filename = file.getFileName().toString().replace(".properties", "");
            boolean direction = Boolean.parseBoolean(outgoing);
            if (filename.startsWith("in-") || filename.startsWith("out-")) {
                if (filename.startsWith("out-") != direction) throw new IOException("Invalid message direction");
                filename = filename.substring(filename.indexOf('-') + 1);
            }
            long time = Long.parseLong(AtomicFiles.required(v, "time"));
            // Before receipt-time ordering, incoming `time` was the sender timestamp.
            long senderTime = Long.parseLong(v.getProperty("senderTime", Long.toString(time)));
            AttachmentRecord attachment=v.containsKey("attachment")?AttachmentRecord.decode(v.getProperty("attachment")):null;
            if(attachment!=null&&(!attachment.info.id.equals(uuid(filename))||attachment.outgoing!=direction))throw new IOException("Conflicting attachment identity");
            return new Message(uuid(filename), AtomicFiles.required(v, "body"), time, direction, status, senderTime,attachment);
        } catch (IllegalArgumentException e) { throw new IOException("Corrupt message", e); }
    }
    public synchronized List<Message> messages(String peer) throws IOException {
        Path directory = messagesPath(peer); if (!Files.exists(directory)) return List.of();
        // Keep only the most recent 200 in memory; all other files remain on disk.
        Comparator<Message> order = Comparator.comparingLong(Message::time).thenComparing(Message::id).thenComparing(Message::outgoing);
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
        Path file = messageFile(peer, id, true); if (!Files.exists(file)) return;
        Message m = readMessage(file);
        if (m.outgoing()) writeMessage(file, new Message(m.id(), m.body(), m.time(), true, status, m.senderTime(),m.attachment()));
    }
    public synchronized void unknown(String peer) throws IOException {
        Path directory = messagesPath(peer); if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                Message m = readMessage(file);
                if (m.outgoing() && m.status().equals("pending")) writeMessage(file, new Message(m.id(), m.body(), m.time(), true, "unknown", m.senderTime(),m.attachment()));
            }
        }
    }
    public synchronized void revoke(String id) throws IOException {
        Peer p = peer(id); if (p != null) peer(new Peer(p.id(), p.name(), "", p.endpoint()));
    }
    public synchronized Path attachmentDirectory(String peer)throws IOException {
        Path base=root.resolve("attachments");if(Files.isSymbolicLink(base))throw new IOException("Unsafe attachment root");
        Files.createDirectories(base);AtomicFiles.privatePermissions(base,true);Path directory=base.resolve(uuid(peer));
        if(Files.isSymbolicLink(directory))throw new IOException("Unsafe attachment directory");return directory;
    }
    public synchronized Path attachmentFile(String peer,AttachmentInfo info)throws IOException {return AttachmentTransfer.file(attachmentDirectory(peer),info);}
    public synchronized Path attachmentsRoot()throws IOException {Path base=root.resolve("attachments");if(Files.isSymbolicLink(base))throw new IOException("Unsafe attachment root");Files.createDirectories(base);AtomicFiles.privatePermissions(base,true);return base;}
    public synchronized Path attachmentFile(String peer,AttachmentInfo info,boolean outgoing)throws IOException {return AttachmentTransfer.file(attachmentDirectory(peer),info,outgoing);}
    public synchronized void attachment(String peer,AttachmentRecord record)throws IOException {
        Path file=messageFile(peer,record.info.id,record.outgoing);Message previous=Files.exists(file)?readMessage(file):null;
        if(previous!=null&&(previous.attachment()==null||!previous.attachment().mayReplace(record)))throw new IOException("Conflicting attachment ID");
        String state=record.outgoing?(record.state.equals("delivered")?"delivered":record.active()?"pending":"unknown"):"received";
        writeMessage(file,new Message(record.info.id,record.info.name,previous==null?System.currentTimeMillis():previous.time(),record.outgoing,state,record.info.time,record));
    }
    private void recoverAttachments(String peer)throws IOException {
        Set<String> received=new HashSet<>();Path directory=messagesPath(peer);
        if(Files.exists(directory))try(var files=Files.list(directory)){for(Path file:files.filter(f->f.toString().endsWith(".properties")).toList()){
            Message m=readMessage(file);if(m.attachment()==null)continue;AttachmentRecord r=m.attachment().recovered();
            if(!r.equals(m.attachment()))writeMessage(file,new Message(m.id(),m.body(),m.time(),m.outgoing(),m.outgoing()?"unknown":"received",m.senderTime(),r));
            if(!r.outgoing&&r.state.equals("received")||r.outgoing&&r.state.equals("delivered")&&r.info.mime.startsWith("image/"))received.add(r.info.id);
        }}
        Path contents=attachmentDirectory(peer);if(Files.exists(contents))AttachmentTransfer.clean(contents,received);
    }
    public synchronized void clear(String id) throws IOException {
        Path contents=attachmentDirectory(id);if(Files.exists(contents))AttachmentTransfer.clear(contents);
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
        String initial = new Strings(existingIdentity ? "zh-Hans" : language()).text(
                existingIdentity ? "defaultNicknameWindows" : DesktopPlatform.key("defaultNickname"));
        setSetting("nickname", initial);
        return initial;
    }
    public synchronized void setSetting(String key, String value) throws IOException {
        if (key.equals("language")) value = LanguageRegistry.normalizeSelection(value);
        Properties v = AtomicFiles.read(root.resolve("settings.properties")); v.setProperty(key, value); AtomicFiles.write(root.resolve("settings.properties"), v);
    }
    public synchronized void close() throws IOException { if (lock.isValid()) lock.release(); lockChannel.close(); }
}
