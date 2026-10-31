package dev.ghost.wozai.next;

import com.google.gson.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.*;
import dev.ghost.nearbyim.verification.QrCodes;
import dev.ghost.wozai.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Private stdin/stdout adapter. The original DesktopClient owns all wire/model behavior. */
public final class BridgeMain implements AutoCloseable {
    private static final int MAX_LINE = 2 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final BufferedWriter output;
    private final Path profile;
    private final DesktopStore store;
    private final DesktopIdentity.Identity identity;
    private final DesktopClient client;
    private final BridgeCatalog catalog;
    private final LanDiscovery discovery = new LanDiscovery();
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), daemon("next-commands"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(daemon("next-state"));
    private final ExecutorService inquiryWorker = Executors.newSingleThreadExecutor(daemon("next-bluetooth"));
    private final ConcurrentMap<String, LanDiscovery.Nearby> lanPeers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DesktopBluetooth.Device> bluetoothPeers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean eventPending = new AtomicBoolean();
    private final AtomicLong inquiryGeneration = new AtomicLong();
    private final Object selectionLock = new Object();
    private final Map<String, ValidatedFile> validatedFiles = new ConcurrentHashMap<>();
    private volatile DesktopClient.State state = new DesktopClient.State("idle", null, null, List.of(), false, "");
    private volatile DesktopClient.Request approval;
    private volatile DesktopBluetooth.Inquiry inquiry;
    private volatile boolean searchingLan, searchingBluetooth;
    private volatile boolean quit;
    private String selected;
    private long selectionRevision;
    private SelectionToken selectionToken;
    private record SelectionToken(String token, long revision, IdentityVerification identity) { }
    private record ValidatedFile(Path path, long size, long modified, Object fileKey, String hash) { }

    private static ThreadFactory daemon(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    private BridgeMain(Path profile, OutputStream protocol) throws Exception {
        this.profile = profile;
        output = new BufferedWriter(new OutputStreamWriter(protocol, StandardCharsets.UTF_8));
        store = new DesktopStore(profile);
        DesktopClient opened = null;
        try {
            catalog = new BridgeCatalog(store.language());
            identity = DesktopIdentity.load(profile.resolve("identity.properties"));
            opened = new DesktopClient(store, identity, new DesktopClient.Listener() {
                public void changed(DesktopClient.State value) {
                    state = value;
                    if (!value.phase().equals("consent")) approval = null;
                    synchronized (selectionLock) {
                        if (selected == null && value.peer() != null && value.phase().equals("ready")) {
                            selected = value.peer().id(); selectionRevision++; selectionToken = null;
                        }
                    }
                    BridgeMain.this.changed();
                }
                public void request(DesktopClient.Request request) { approval = request; BridgeMain.this.changed(); }
                public void notice(UiText text) { BridgeMain.this.notice(text); }
                public void attachmentProgress(String peer) { BridgeMain.this.changed(); }
                public void avatarChanged(String key) { BridgeMain.this.changed(); }
            });
            client = opened;
            await(client.refresh());
        } catch (Exception failure) {
            if (opened != null) opened.close();
            store.close(); throw failure;
        }
    }

    public static void main(String[] args) {
        // Reserve the real stdout descriptor for protocol even if a dependency prints diagnostics.
        OutputStream protocol = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (args.length != 2 || !args[0].equals("--profile") || args[1].isBlank()) {
            System.err.println("Usage: BridgeMain --profile PATH"); System.exit(2); return;
        }
        try (BridgeMain bridge = new BridgeMain(Path.of(args[1]).toAbsolutePath().normalize(), protocol)) {
            Thread hook = new Thread(bridge::close, "next-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try { bridge.run(System.in); }
            finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { } }
        } catch (Exception failure) {
            // Do not print request bodies, keys, or identity material.
            System.err.println("Bridge startup/shutdown failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private void run(InputStream input) throws IOException {
        changed();
        // A separate reader preserves EOF shutdown even while a filesystem operation is pending.
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean overflow = false;
        for (int next; !closed.get() && !quit && (next = input.read()) != -1;) {
            if (next == '\n') {
                if (overflow) reply(null, false, null, catalog.text(UiText.of("error")));
                else if (line.size() != 0) accept(line.toByteArray());
                line.reset(); overflow = false;
            } else if (!overflow) {
                if (line.size() >= MAX_LINE) { overflow = true; line.reset(); }
                else line.write(next);
            }
        }
        // A final unterminated JSONL request is not executed on EOF.
    }

    private void accept(byte[] bytes) {
        String id = null;
        try {
            String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) throw new IOException("Invalid request");
            JsonObject request = parsed.getAsJsonObject();
            id = required(request, "id");
            if (id.length() > 128) throw new IOException("Invalid request ID");
            String op = required(request, "op");
            JsonObject args = request.has("args") && request.get("args").isJsonObject() ? request.getAsJsonObject("args") : new JsonObject();
            String requestId = id;
            commands.execute(() -> {
                try {
                    Object data = operation(op, args);
                    reply(requestId, true, data, null);
                    if (op.equals("quit")) { quit = true; close(); try { System.in.close(); } catch (IOException ignored) { } }
                    else changed();
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    reply(requestId, false, null, catalog.error(failure));
                }
            });
        } catch (Exception invalid) { reply(id, false, null, catalog.error(invalid)); }
    }

    private Object operation(String op, JsonObject args) throws Exception {
        return switch (op) {
            case "snapshot" -> snapshot();
            case "messages" -> messagePage(requireSelected(args), optional(args, "before"), args.has("limit") ? Math.toIntExact(number(args,"limit")) : 20);
            case "refresh" -> { await(client.refresh()); yield snapshot(); }
            case "quit" -> null;
            case "select" -> {
                String peer = uuid(required(args, "peerId"));
                synchronized (selectionLock) {
                    if (!peer.equals(selected)) { selected = peer; selectionRevision++; selectionToken = null; }
                }
                yield snapshot();
            }
            case "draft" -> { await(client.draft(uuid(required(args, "peerId")), required(args, "text"))); yield null; }
            case "send" -> send(args);
            case "listen" -> { listen(args); yield null; }
            case "search" -> { search(required(args, "mode")); yield null; }
            case "stopSearch" -> { stopSearch(required(args, "mode")); yield null; }
            case "connect" -> {
                String expected = optional(args, "expectedId");
                if (expected != null) expected = uuid(expected);
                await(client.connect(required(args, "endpoint"), expected)); yield null;
            }
            case "disconnect" -> { await(client.disconnect()); approval = null; invalidateToken(); yield null; }
            case "approve", "reject" -> {
                DesktopClient.Request request = pending(args);
                if (op.equals("approve")) await(client.approve(request, bool(args, "remember")));
                else await(client.reject(request));
                if (approval == request) approval = null;
                yield null;
            }
            case "revoke" -> { await(client.revoke(uuid(required(args, "peerId")))); invalidateToken(); yield null; }
            case "clear" -> { await(client.clear(uuid(required(args, "peerId")))); validatedFiles.clear(); invalidateToken(); yield null; }
            case "settings" -> { settings(args); yield null; }
            case "files" -> {
                IdentityVerification expected = requireToken(args);
                JsonArray paths = array(args, "paths");
                if (paths.isEmpty() || paths.size() > AttachmentBatchQueue.MAX_PENDING) throw localized("attachmentBatchLimit", AttachmentBatchQueue.MAX_PENDING);
                List<Path> selection = new ArrayList<>();
                for (JsonElement path : paths) selection.add(Path.of(string(path)));
                yield await(client.sendAttachments(expected, selection));
            }
            case "attachment" -> attachment(args);
            case "retryFile" -> await(client.retryAttachmentWithSelection(requireToken(args), uuid(required(args, "id")), Path.of(required(args, "path"))));
            case "batch" -> { String peer = requireSelected(args); requireReady(peer); await(client.batchAction(peer, required(args, "action"))); yield null; }
            case "export", "openPath" -> fileOperation(op, args);
            case "verification" -> verificationMap(uuid(required(args, "peerId")));
            case "verify" -> {
                IdentityVerification expected = requireToken(args);
                if (!await(client.verifyIdentity(expected, optional(args, "scanned")))) throw localized("verificationConnectionChanged");
                yield verificationMap(expected.peerId);
            }
            case "qrImage" -> {
                String peer = uuid(required(args, "peerId"));
                IdentityVerification verification = await(client.verification(peer));
                if (verification == null) throw localized("notConnected");
                int edge = 384; boolean[] bits = QrCodes.encode(verification.code.qrPayload(), edge);
                BufferedImage image = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) image.setRGB(x, y, bits[y * edge + x] ? 0 : 0xffffff);
                yield encodePng(image);
            }
            case "readQr" -> {
                BufferedImage image = readImage(Path.of(required(args, "path")), 2048, 32L * 1024 * 1024);
                if (image == null) throw localized("verificationReadFailed");
                String payload = QrCodes.decode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), image.getWidth(), image.getHeight());
                if (payload == null) throw localized("verificationReadFailed");
                yield payload;
            }
            case "avatar" -> {
                String peer = optional(args, "peerId");
                AvatarSnapshot avatar;
                if (peer == null || peer.equals(identity.id())) avatar = await(client.localAvatar());
                else {
                    DesktopStore.Peer contact = findPeer(uuid(peer));
                    if (contact == null || contact.publicKey().isEmpty()) yield map("png", "", "revision", 0L);
                    avatar = await(client.avatar(contact.publicKey()));
                }
                yield avatarMap(avatar);
            }
            case "saveAvatar" -> {
                String png = required(args, "png");
                if (png.length() > (AvatarSpec.MAX_BYTES * 4L + 2) / 3 + 4) throw localized("avatarFailed");
                yield avatarMap(await(client.saveAvatar(number(args, "revision"), Base64.getDecoder().decode(png))));
            }
            case "resetAvatar" -> avatarMap(await(client.resetAvatar(number(args, "revision"))));
            case "observed" -> {
                String peer = requireSelected(args);
                JsonArray ids = array(args, "ids");
                if (ids.size() > 32) throw new IOException("Too many observations");
                Set<String> eligible = new HashSet<>();
                for (DesktopStore.Message message : await(client.messages(peer))) if (!message.outgoing() && message.attachment() == null) eligible.add(message.id());
                List<String> observed = new ArrayList<>();
                for (JsonElement value : ids) { String id = uuid(string(value)); if (!eligible.contains(id)) throw new IOException("Invalid observation"); observed.add(id); }
                await(client.observedMessages(peer, observed)); yield null;
            }
            default -> throw new IOException("Unknown operation");
        };
    }

    private Object send(JsonObject args) throws Exception {
        String peer = requireSelected(args), text = required(args, "text");
        requireReady(peer);
        if (text.isBlank()) throw localized("emptyMessage");
        String draft = await(client.draft(peer));
        boolean sent = await(client.send(text));
        if (!sent) throw localized("notConnected");
        // Only the exact draft submitted by this send is cleared, after the kernel's durable save.
        if (draft.equals(text) && await(client.draft(peer)).equals(text)) await(client.draft(peer, ""));
        return true;
    }

    private void listen(JsonObject args) throws Exception {
        String mode = required(args, "mode"); boolean enabled = bool(args, "enabled");
        switch (mode) {
            case "lan" -> {
                if (enabled) { DesktopClient.Listening listening = await(client.listen()); startLan(listening.port()); }
                else { stopSearch("lan"); await(client.stopListening()); }
            }
            case "bluetooth" -> { if (enabled) await(client.listenBluetooth()); else await(client.stopBluetoothListening()); }
            default -> throw new IOException("Invalid transport");
        }
    }

    private void search(String mode) throws Exception {
        switch (mode) {
            case "lan" -> startLan(state.listening() == null ? 0 : state.listening().port());
            case "bluetooth" -> {
                stopSearch("bluetooth");
                DesktopBluetooth.Inquiry opened = DesktopBluetooth.openInquiry();
                inquiry = opened; searchingBluetooth = true; bluetoothPeers.clear();
                long run = inquiryGeneration.incrementAndGet();
                inquiryWorker.execute(() -> {
                    try {
                        for (DesktopBluetooth.Device device : opened.scan(10)) if (!closed.get() && run == inquiryGeneration.get() && bluetoothPeers.size() < 128) bluetoothPeers.put(device.address(), device);
                    } catch (IOException failure) { if (!closed.get() && run == inquiryGeneration.get()) notice(UiText.of("bluetoothScanFailedWindows")); }
                    finally {
                        try { opened.close(); } catch (IOException ignored) { }
                        if (run == inquiryGeneration.get()) { searchingBluetooth = false; inquiry = null; changed(); }
                    }
                });
            }
            default -> throw new IOException("Invalid transport");
        }
    }

    private void startLan(int port) throws IOException {
        searchingLan = true;
        discovery.start(identity.id(), store.nickname(), port, new LanDiscovery.Listener() {
            public void found(LanDiscovery.Nearby peer) {
                if (searchingLan && !closed.get() && (lanPeers.size() < 128 || lanPeers.containsKey(peer.service()))) { lanPeers.put(peer.service(), peer); changed(); }
            }
            public void lost(String service) { lanPeers.remove(service); changed(); }
            public void failed() { notice(UiText.of("discoveryFailed")); }
            public void networkChanged() { client.refresh().whenComplete((value, failure) -> changed()); }
        });
    }

    private void stopSearch(String mode) throws IOException {
        switch (mode) {
            case "lan" -> { searchingLan = false; discovery.stop(); lanPeers.clear(); }
            case "bluetooth" -> {
                inquiryGeneration.incrementAndGet(); searchingBluetooth = false;
                DesktopBluetooth.Inquiry previous = inquiry; inquiry = null;
                if (previous != null) previous.close();
            }
            default -> throw new IOException("Invalid transport");
        }
    }

    private Object attachment(JsonObject args) throws Exception {
        String peer = requireSelected(args), id = uuid(required(args, "id")), action = required(args, "action");
        boolean outgoing = bool(args, "outgoing");
        AttachmentRecord record = attachmentRecord(peer, id, outgoing);
        if (action.equals("retry") || action.equals("retryBatch")) {
            if (!outgoing || !record.state.equals("failed")) throw localized("attachmentFailed");
            IdentityVerification expected = requireToken(args);
            if (action.equals("retry")) return await(client.retryAttachment(expected, id));
            return await(client.retryFailedBatch(expected, id));
        }
        requireReady(peer);
        if (args.has("generation")) requireToken(args);
        if (outgoing && (action.equals("accept") || action.equals("reject"))) throw new IOException("Invalid direction");
        await(client.attachmentAction(peer, id, outgoing, action)); return null;
    }

    private Object fileOperation(String op, JsonObject args) throws Exception {
        String peer = requireSelected(args);
        AttachmentRecord record = attachmentRecord(peer, uuid(required(args, "id")), bool(args, "outgoing"));
        requireComplete(record);
        if (op.equals("export")) {
            await(client.exportAttachment(peer, record, Path.of(required(args, "destination"))));
            return null;
        }
        if (!record.info.mime.startsWith("image/") && !record.info.canOpenExternally()) throw localized("attachmentSaveBeforeOpening");
        Path source = await(client.attachmentPath(peer, record));
        validateFile(peer, record, source, false);
        return await(client.attachmentOpenPath(peer, record)).toString();
    }

    private void settings(JsonObject args) throws Exception {
        // Validate every field before performing any setting mutation.
        String nickname = optional(args, "nickname"), theme = optional(args, "theme"), language = optional(args, "language");
        if (nickname != null) Protocol.write(OutputStream.nullOutputStream(), new Frame(Frame.HELLO,identity.id(),nickname.trim(),System.currentTimeMillis()));
        if (nickname != null && (nickname.isBlank() || nickname.codePointCount(0, nickname.length()) > 32 || nickname.codePoints().anyMatch(Character::isISOControl))) throw localized("nicknameHint");
        if (theme != null && !Set.of("light", "dark", "system").contains(theme)) throw new IOException("Invalid theme");
        if (language != null && !language.equals("system") && !LanguageRegistry.normalizeSelection(language).equals(language)) throw new IOException("Invalid language");
        int font = args.has("fontSize") ? Math.toIntExact(number(args, "fontSize")) : -1;
        if (args.has("fontSize") && (font < 0 || font > 2)) throw new IOException("Invalid font size");
        long quota = args.has("quota") ? number(args, "quota") : -1;
        if (args.has("quota") && quota < 0) throw new IOException("Invalid quota");
        Boolean read = args.has("readReceipts") ? bool(args, "readReceipts") : null;
        if (nickname != null) await(client.setting("nickname", nickname.trim()));
        if (theme != null) await(client.setting("theme", theme));
        if (language != null) { await(client.setting("language", language)); catalog.language(language); }
        if (font != -1) await(client.setting("textSize", Integer.toString(font)));
        if (read != null) await(client.setReadReceiptsEnabled(read));
        if (quota != -1) await(client.attachmentQuota(quota));
        if (nickname != null && searchingLan) startLan(state.listening() == null ? 0 : state.listening().port());
        await(client.refresh());
    }

    private Map<String, Object> snapshot() throws Exception {
        DesktopClient.State current = state;
        String peer;
        synchronized (selectionLock) { peer = selected; }
        Map<String, DesktopStore.Message> summaries = await(client.summaries());
        List<Object> history = new ArrayList<>();
        for (DesktopStore.Peer contact : current.history()) {
            DesktopStore.Message summary = summaries.get(contact.id());
            history.add(peerMap(contact.id(), contact.name(), contact.endpoint(), contact.publicKey(),
                    summary == null ? "" : summary.body(), summary == null ? 0 : summary.time(), !contact.publicKey().isEmpty(), ""));
        }
        List<Object> nearby = new ArrayList<>();
        for (LanDiscovery.Nearby contact : lanPeers.values()) nearby.add(peerMap(contact.id(), contact.name(), contact.endpoint(), "", "", 0, false, "lan"));
        for (DesktopBluetooth.Device contact : bluetoothPeers.values()) nearby.add(peerMap(null, contact.name(), "bluetooth:" + contact.address(), "", "", 0, false, "bluetooth"));
        Map<String,Object> page = peer == null ? map("messages",List.of(),"hasMore",false,"nextBefore",null) : messagePage(peer,null,20);
        Map<String, Object> verification = peer == null ? null : verificationMap(peer);
        DesktopClient.Request request = approval;
        Object approvalMap = request == null ? null : map("token", Long.toString(request.token()), "name", request.peer().name(),
                "id", request.peer().id(), "fingerprint", fingerprint(request.publicKey()));
        List<Object> batches = new ArrayList<>();
        if (peer != null) for (AttachmentBatchQueue.Item item : client.batchItems(peer)) batches.add(map(
                "batchId", item.batchId(), "id", item.info().id, "name", item.info().name, "mime", item.info().mime, "size", item.info().size,
                "state", item.state(), "transferred", item.transferred(), "retryOf", item.retryOf(), "retryState", client.retryState(peer, item.info().id).name().toLowerCase(Locale.ROOT)));
        DesktopBluetooth.Status bluetooth = DesktopBluetooth.status();
        return map("localId", identity.id(), "nickname", store.nickname(), "version", LanguageRegistry.VERSION,
                "phase", current.phase(), "connectedPeerId", current.peer() == null ? null : current.peer().id(), "transport", current.transport(),
                "lanListening", current.listening() != null, "bluetoothListening", current.bluetoothListening(),
                "endpoints", current.listening() == null ? List.of() : current.listening().endpoints(),
                "listenPort", current.listening() == null ? 0 : current.listening().port(),
                "selectedPeerId", peer, "history", history, "nearby", nearby, "messages", page.get("messages"), "messagesHasMore",page.get("hasMore"),"messagesBefore",page.get("nextBefore"),
                "draft", peer == null ? "" : await(client.draft(peer)),
                "canSend", peer != null && current.peer() != null && peer.equals(current.peer().id()) && current.phase().equals("ready"),
                "canAttach", verification != null, "textLimit", client.textLimit(), "readReceipts", client.readReceiptsEnabled(),
                "canObserve", peer != null && client.canObserveMessages(peer),
                "theme", store.setting("theme", "light"), "language", store.language(), "fontSize", fontSize(), "quota", client.attachmentQuota(),
                "verification", verification, "generation", verification == null ? null : verification.get("generation"), "approval", approvalMap,
                "strings", catalog.strings(), "languages", LanguageRegistry.languages(), "effectiveLanguage", catalog.effectiveLanguage(), "rtl", catalog.rtl(),
                "batchItems", batches, "diagnostics", client.transferDiagnostics(peer) + discovery.diagnostics(),
                "searchingLan", searchingLan, "searchingBluetooth", searchingBluetooth, "dataPath", profile.toString(),
                "bluetoothSupported", bluetooth.supported(), "bluetoothAvailable", bluetooth.available(), "bluetoothDetail", bluetooth.detail());
    }

    private Map<String,Object> messagePage(String peer,String before,int requested) throws Exception {
        List<DesktopStore.Message> all=await(client.messages(peer));
        int end=all.size(), limit=Math.max(1,Math.min(20,requested));
        if(before!=null) { boolean found=false; for(int i=0;i<all.size();i++) if((all.get(i).id()+":"+all.get(i).outgoing()).equals(before)){end=i;found=true;break;} if(!found) throw new IOException("History cursor expired"); }
        List<Object> result=new ArrayList<>(); int bytes=0,index=end;
        while(index>0&&result.size()<limit){
            DesktopStore.Message m=all.get(index-1);
            Object value=map("id",m.id(),"body",m.body(),"time",m.time(),"outgoing",m.outgoing(),"status",m.status(),"attachment",m.attachment()==null?null:attachmentMap(peer,m.attachment()));
            int cost=JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
            if(!result.isEmpty()&&bytes+cost>600000)break;
            result.add(0,value);bytes+=cost;index--;
        }
        return map("messages",result,"hasMore",index>0,"nextBefore",index>0?all.get(index).id()+":"+all.get(index).outgoing():null);
    }

    private int fontSize() throws IOException {
        try { return Math.max(0, Math.min(2, Integer.parseInt(store.setting("textSize", "0")))); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private Object attachmentMap(String peer, AttachmentRecord record) throws Exception {
        Path path = null;
        return map("id", record.info.id, "name", record.info.name, "mime", record.info.mime, "size", record.info.size,
                "transferred", record.transferred, "state", record.state, "version", record.info.version, "route", record.route,
                "path", path == null ? null : path.toString(), "outgoing", record.outgoing,
                "safeToOpen", record.info.canOpenExternally(), "retryState", client.retryState(peer, record.info.id).name().toLowerCase(Locale.ROOT),
                "hasRetryableBatch", client.hasRetryableBatch(peer, record.info.id), "pathKey", client.attachmentTransferPath(peer, record).key());
    }

    private void validateFile(String peer, AttachmentRecord record, Path source, boolean allowCache) throws IOException {
        requireComplete(record);
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != record.info.size) throw localized("attachmentUnavailable");
        String key = peer + ":" + record.info.id + ":" + record.outgoing;
        ValidatedFile cached = validatedFiles.get(key);
        if (allowCache && cached != null && cached.path.equals(source) && cached.size == attributes.size()
                && cached.modified == attributes.lastModifiedTime().toMillis() && Objects.equals(cached.fileKey, attributes.fileKey()) && cached.hash.equals(record.info.hash)) return;
        VerifiedAttachmentCopy.copy(source, OutputStream.nullOutputStream(), record.info, closed::get);
        validatedFiles.put(key, new ValidatedFile(source, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey(), record.info.hash));
    }

    private Map<String, Object> verificationMap(String peer) throws Exception {
        IdentityVerification verification = await(client.verification(peer));
        if (verification == null) { synchronized (selectionLock) { if (peer.equals(selected)) selectionToken = null; } return null; }
        synchronized (selectionLock) {
            if (!peer.equals(selected)) return null;
            if (selectionToken == null || selectionToken.revision != selectionRevision || !selectionToken.identity.sameIdentity(verification)) {
                selectionToken = new SelectionToken(UUID.randomUUID().toString(), selectionRevision, verification);
            }
            return map("peerId", peer, "display", verification.code.display(), "qrPayload", verification.code.qrPayload(),
                    "verified", verification.verified, "generation", selectionToken.token);
        }
    }

    private IdentityVerification requireToken(JsonObject args) throws Exception {
        String peer = requireSelected(args), token = required(args, "generation");
        synchronized (selectionLock) {
            SelectionToken current = selectionToken;
            if (current == null || !current.token.equals(token) || current.revision != selectionRevision
                    || !current.identity.peerId.equals(peer) || !client.verificationCurrent(current.identity)) throw localized("verificationConnectionChanged");
            return current.identity;
        }
    }

    private void invalidateToken() { synchronized (selectionLock) { selectionToken = null; } }
    private String requireSelected(JsonObject args) throws IOException {
        String peer = uuid(required(args, "peerId"));
        synchronized (selectionLock) { if (!peer.equals(selected)) throw localized("notConnected"); }
        return peer;
    }
    private void requireReady(String peer) throws IOException {
        DesktopClient.State current = state;
        if (!current.phase().equals("ready") || current.peer() == null || !peer.equals(current.peer().id())) throw localized("notConnected");
    }
    private DesktopClient.Request pending(JsonObject args) throws IOException {
        DesktopClient.Request request = approval;
        if (request == null || !Long.toString(request.token()).equals(required(args, "token")) || !state.phase().equals("consent")) throw localized("notConnected");
        return request;
    }
    private DesktopStore.Peer findPeer(String peer) {
        DesktopClient.State current = state;
        if (current.peer() != null && peer.equals(current.peer().id())) return current.peer();
        return current.history().stream().filter(value -> peer.equals(value.id())).findFirst().orElse(null);
    }
    private AttachmentRecord attachmentRecord(String peer, String id, boolean outgoing) throws Exception {
        for (DesktopStore.Message message : await(client.messages(peer))) if (message.id().equals(id) && message.outgoing() == outgoing && message.attachment() != null) return message.attachment();
        throw localized("attachmentUnavailable");
    }
    private static void requireComplete(AttachmentRecord record) throws IOException {
        if (!Set.of("received", "delivered").contains(record.state) || record.info.hash == null || record.transferred != record.info.size) throw localized("attachmentUnavailable");
    }

    private void changed() {
        if (closed.get() || !eventPending.compareAndSet(false, true)) return;
        try {
            events.schedule(() -> {
                eventPending.set(false);
                if (closed.get()) return;
                try { emit(map("event", "state", "data", snapshot())); }
                catch (Exception failure) { if (!closed.get()) notice(UiText.of("storageFailure")); }
            }, 80, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { eventPending.set(false); }
    }
    private void notice(UiText text) { if (!closed.get()) emit(map("event", "notice", "data", catalog.text(text))); }
    private void reply(String id, boolean ok, Object data, String error) {
        Map<String, Object> value = ok ? map("id", id, "ok", true, "data", data) : map("id", id, "ok", false, "error", error);
        emit(value);
    }
    private void emit(Object object) {
        String line = JSON.toJson(object);
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE) {
            // Preserve the correlation ID instead of silently hanging a UI request.
            Object id = object instanceof Map<?, ?> values ? values.get("id") : null;
            line = JSON.toJson(map("id", id, "ok", false, "error", catalog.text(UiText.of("error"))));
        }
        synchronized (output) {
            try { output.write(line); output.newLine(); output.flush(); }
            catch (IOException disconnected) { quit = true; }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        commands.shutdownNow(); events.shutdownNow();
        try { stopSearch("bluetooth"); } catch (IOException ignored) { }
        inquiryWorker.shutdownNow(); discovery.close(); client.close();
        try { store.close(); } catch (IOException ignored) { }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(60, TimeUnit.SECONDS); }
    private static String required(JsonObject args, String key) throws IOException {
        if (!args.has(key) || args.get(key).isJsonNull()) throw new IOException("Missing argument");
        return string(args.get(key));
    }
    private static String optional(JsonObject args, String key) throws IOException { return !args.has(key) || args.get(key).isJsonNull() ? null : string(args.get(key)); }
    private static String string(JsonElement value) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected string");
        return value.getAsString();
    }
    private static boolean bool(JsonObject args, String key) throws IOException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isBoolean()) throw new IOException("Expected boolean");
        return args.get(key).getAsBoolean();
    }
    private static long number(JsonObject args, String key) throws IOException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) throw new IOException("Expected integer");
        try { return args.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new IOException("Invalid integer", invalid); }
    }
    private static JsonArray array(JsonObject args, String key) throws IOException {
        if (!args.has(key) || !args.get(key).isJsonArray()) throw new IOException("Expected array");
        return args.getAsJsonArray(key);
    }
    private static String uuid(String value) throws IOException {
        if (!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IOException("Invalid identity");
        return value.toLowerCase(Locale.ROOT);
    }
    private static LocalizedIOException localized(String key, Object... args) { return new LocalizedIOException(UiText.of(key, args)); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
    private static Object peerMap(String id, String name, String endpoint, String publicKey, String preview, long time, boolean trusted, String transport) {
        return map("id", id, "name", name, "endpoint", endpoint, "publicKey", publicKey, "preview", preview.length()>200?preview.substring(0,200):preview, "time", time, "trusted", trusted, "transport", transport);
    }
    private static Object avatarMap(AvatarSnapshot avatar) { return map("png", Base64.getEncoder().encodeToString(avatar.png()), "revision", avatar.profile().revision); }
    private static String fingerprint(String key) {
        try { return AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))).replaceAll("(.{8})(?!$)", "$1 "); }
        catch (Exception invalid) { return ""; }
    }
    private static String encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder unavailable");
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private static BufferedImage readImage(Path path, int maxSide, long maxBytes) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) throw new IOException("Invalid image file");
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 100_000_000) throw new IOException("Image dimensions too large");
                ImageReadParam params = reader.getDefaultReadParam();
                int sampling = Math.max(1, (Math.max(width, height) + maxSide - 1) / maxSide);
                params.setSourceSubsampling(sampling, sampling, 0, 0);
                return reader.read(0, params);
            } finally { reader.dispose(); }
        }
    }
}
