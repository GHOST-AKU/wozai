package dev.ghost.wozai.next;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Exercises the production JSONL child and unchanged communication kernel, without mocks. */
public final class BridgeIntegrationTests {
    private static int checks;
    private static final Gson JSON = new Gson();
    private static final class Child implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BlockingQueue<JsonObject> output = new LinkedBlockingQueue<>();
        final StringBuilder diagnostic = new StringBuilder();
        int sequence;
        Child(Path profile) throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                    "dev.ghost.wozai.next.BridgeMain", "--profile", profile.toString()).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread stdout = new Thread(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    for (String line; (line = reader.readLine()) != null;) output.add(JsonParser.parseString(line).getAsJsonObject());
                } catch (Exception error) { synchronized (diagnostic) { diagnostic.append(error); } }
            });
            stdout.setDaemon(true); stdout.start();
            Thread stderr = new Thread(() -> {
                try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
                    for (String line; (line = reader.readLine()) != null;) synchronized (diagnostic) { diagnostic.append(line).append('\n'); }
                } catch (IOException ignored) { }
            });
            stderr.setDaemon(true); stderr.start();
        }
        JsonObject reply(String op, JsonObject args) throws Exception {
            String id = Integer.toString(++sequence);
            input.write(JSON.toJson(obj("id", id, "op", op, "args", args))); input.newLine(); input.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                JsonObject line = output.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && line.has("id") && id.equals(line.get("id").getAsString())) return line;
                if (!process.isAlive() && output.isEmpty()) throw new AssertionError("Bridge exited before replying to " + op + ": " + diagnostic);
            }
            throw new AssertionError("Bridge did not reply to " + op + ": " + diagnostic);
        }
        JsonElement call(String op, JsonObject args) throws Exception {
            JsonObject reply = reply(op, args);
            check(reply.get("ok").getAsBoolean(), op + " failed: " + reply);
            return reply.get("data");
        }
        JsonObject snapshot() throws Exception { return call("snapshot", obj()).getAsJsonObject(); }
        JsonObject until(Predicate<JsonObject> test) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            JsonObject last = null;
            while (System.nanoTime() < deadline) { last = snapshot(); if (test.test(last)) return last; Thread.sleep(40); }
            if (last != null) last.remove("strings");
            throw new AssertionError("Expected child state was not reached: " + last + " / " + diagnostic);
        }
        void fails(String op, JsonObject args) throws Exception { check(!reply(op, args).get("ok").getAsBoolean(), op + " incorrectly succeeded"); }
        @Override public void close() throws Exception {
            input.close();
            check(process.waitFor(8, TimeUnit.SECONDS), "EOF must terminate the child and release its session/profile");
            if (process.isAlive()) process.destroyForcibly();
            check(process.exitValue() == 0, "Normal EOF exited nonzero: " + diagnostic);
        }
    }
    static JsonObject obj(Object... values) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < values.length; i += 2) result.add((String) values[i], JSON.toJsonTree(values[i + 1]));
        return result;
    }
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static boolean ready(JsonObject state) { return state.get("phase").getAsString().equals("ready"); }
    static String string(JsonObject obj, String key) { return obj.get(key).getAsString(); }
    static JsonObject firstMessage(JsonObject state, String text) {
        for (JsonElement element : state.getAsJsonArray("messages")) {
            JsonObject message = element.getAsJsonObject();
            if (text.equals(string(message, "body"))) return message;
        }
        return null;
    }
    static JsonObject file(JsonObject state, boolean outgoing) {
        for (JsonElement element : state.getAsJsonArray("messages")) {
            JsonObject message = element.getAsJsonObject();
            if (!message.get("attachment").isJsonNull() && message.get("outgoing").getAsBoolean() == outgoing) return message.getAsJsonObject("attachment");
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wozai-next-bridge-test-");
        String aid, bid, generation;
        try {
            try (Child a = new Child(root.resolve("a")); Child b = new Child(root.resolve("b"))) {
                JsonObject freshA = a.snapshot(), freshB = b.snapshot();
                aid = string(freshA, "localId"); bid = string(freshB, "localId");
                check(!aid.equals(bid), "Independent profiles must have separate identities");
                check(!freshA.get("lanListening").getAsBoolean() && !freshA.get("bluetoothListening").getAsBoolean(), "Startup must not receive or discover");
                check(!freshA.get("searchingLan").getAsBoolean(), "Startup must not discover");
                a.fails("send", obj("peerId", bid, "text", "offline"));
                a.fails("approve", obj("token", "invalid", "remember", true));
                a.call("settings", obj("language", "en", "theme", "dark", "fontSize", 1, "readReceipts", true));
                JsonArray emojis=a.call("emoji",obj("category","Smileys & Emotion","query","grinning")).getAsJsonArray();
                check(!emojis.isEmpty(),"Full Unicode catalog must support localized search");
                String picked=string(emojis.get(0).getAsJsonObject(),"text");
                a.call("emojiRecent",obj("text",picked));
                check(string(a.call("emoji",obj("category","recent","query","")).getAsJsonArray().get(0).getAsJsonObject(),"text").equals(picked),"Emoji recent selections must persist through kernel settings");
                a.call("draft", obj("peerId", bid, "text", "durable draft"));
                a.call("select", obj("peerId", bid));
                check(string(a.snapshot(), "draft").equals("durable draft"), "Draft must round-trip through disk");
                b.call("listen", obj("mode", "lan", "enabled", true));
                JsonObject listening = b.snapshot();
                int port = listening.get("listenPort").getAsInt();
                check(port > 0 && !listening.getAsJsonArray("endpoints").isEmpty(), "Integration test requires a private local interface accepted by the unchanged kernel");
                String endpoint = listening.getAsJsonArray("endpoints").get(0).getAsString();
                a.call("connect", obj("endpoint", endpoint, "expectedId", bid));
                JsonObject br = b.until(s -> !s.get("approval").isJsonNull());
                check(!ready(a.snapshot()) && !ready(br), "Unknown incoming peers must wait for explicit consent");
                a.fails("send", obj("peerId", bid, "text", "before consent"));
                b.call("approve", obj("token", br.getAsJsonObject("approval").get("token"), "remember", true));
                a.until(BridgeIntegrationTests::ready); b.until(BridgeIntegrationTests::ready);
                b.call("select", obj("peerId", aid));
                a.fails("send", obj("peerId", UUID.randomUUID().toString(), "text", "wrong conversation"));
                JsonObject verification = a.call("verification", obj("peerId", bid)).getAsJsonObject();
                generation = string(verification, "generation");
                a.fails("verify", obj("peerId", bid, "generation", generation, "scanned", "WOZAI-ID1:" + "0".repeat(64)));
                a.call("verify", obj("peerId", bid, "generation", generation, "scanned", verification.get("qrPayload")));
                check(a.snapshot().getAsJsonObject("verification").get("verified").getAsBoolean(), "Matching QR verifies exact identity");
                String qr = a.call("qrImage", obj("peerId", bid)).getAsString();
                Path qrPath = root.resolve("qr.png"); Files.write(qrPath, Base64.getDecoder().decode(qr));
                check(a.call("readQr", obj("path", qrPath.toString())).getAsString().equals(string(verification, "qrPayload")), "QR must decode the real generated image");
                a.call("send", obj("peerId", bid, "text", "durable draft"));
                check(string(a.snapshot(), "draft").isEmpty(), "Sending only clears the matching draft after durable save");
                JsonObject received = b.until(s -> firstMessage(s, "durable draft") != null);
                String messageId = string(firstMessage(received, "durable draft"), "id");
                a.until(s -> "delivered".equals(string(firstMessage(s, "durable draft"), "status")));
                for (int i = 0; i < 20; i++) a.call("send", obj("peerId", bid, "text", "newer message " + i));
                b.until(s -> firstMessage(s, "newer message 19") != null);
                JsonArray oldStatus = a.call("messageUpdates", obj("peerId", bid, "keys", List.of(messageId + ":true"))).getAsJsonArray();
                check(oldStatus.size() == 1 && string(oldStatus.get(0).getAsJsonObject(), "status").equals("delivered"), "Loaded messages outside the snapshot page must retain current status access");
                check(!oldStatus.get(0).getAsJsonObject().has("body"), "Status refreshes must not retransmit long message bodies");
                a.fails("messageUpdates", obj("peerId", aid, "keys", List.of(messageId + ":true")));
                a.fails("messageUpdates", obj("peerId", bid, "keys", Collections.nCopies(129, messageId + ":true")));
                b.fails("observed", obj("peerId", bid, "ids", List.of(messageId)));
                b.call("observed", obj("peerId", aid, "ids", List.of(messageId)));
                long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String oldReadStatus;
                do {
                    oldReadStatus = string(a.call("messageUpdates", obj("peerId", bid, "keys", List.of(messageId + ":true"))).getAsJsonArray().get(0).getAsJsonObject(), "status");
                    if (!oldReadStatus.equals("read")) Thread.sleep(40);
                } while (!oldReadStatus.equals("read") && System.nanoTime() < readDeadline);
                check(oldReadStatus.equals("read"), "Late read receipts must refresh an older loaded message outside the newest snapshot page");
                Path source = root.resolve("hello.txt"); Files.writeString(source, "Real encrypted attachment\n".repeat(100));
                a.fails("files", obj("peerId", bid, "generation", "stale", "paths", List.of(source.toString())));
                a.call("files", obj("peerId", bid, "generation", generation, "paths", List.of(source.toString())));
                JsonObject offered = b.until(s -> file(s, false) != null);
                String fileId = string(file(offered, false), "id");
                b.fails("openPath", obj("peerId", aid, "id", UUID.randomUUID().toString(), "outgoing", false));
                JsonObject completed = b.until(s -> file(s, false) != null && "received".equals(string(file(s, false), "state")));
                a.until(s -> file(s, true) != null && "delivered".equals(string(file(s, true), "state")));
                check(file(completed, false).get("path").isJsonNull(), "Snapshots must not hash or expose full attachment originals; explicit open validates them");
                Path destination = root.resolve("exported.txt");
                b.call("export", obj("peerId", aid, "id", fileId, "outgoing", false, "destination", destination.toString()));
                check(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(destination)), "Export must preserve transferred payload bytes");
                Path opened = Path.of(b.call("openPath", obj("peerId", aid, "id", fileId, "outgoing", false)).getAsString());
                check(Files.readString(opened).equals(Files.readString(source)), "Safe open must return validated complete data");
                b.fails("export", obj("peerId", aid, "id", fileId, "outgoing", false, "destination", root.resolve("missing/sub/file").toString()));
                a.call("disconnect", obj()); b.until(s -> string(s, "phase").equals("idle"));
                a.fails("verify", obj("peerId", bid, "generation", generation));
                a.call("connect", obj("endpoint", endpoint, "expectedId", bid));
                JsonObject reconnected = a.until(BridgeIntegrationTests::ready); b.until(BridgeIntegrationTests::ready);
                check(!generation.equals(string(reconnected.getAsJsonObject("verification"), "generation")), "Reconnection invalidates selection tokens");
                a.fails("files", obj("peerId", bid, "generation", generation, "paths", List.of(source.toString())));
                a.call("revoke", obj("peerId", bid));
                a.until(s -> !ready(s)); b.until(s -> !ready(s));
                a.call("draft", obj("peerId", bid, "text", "restart draft"));
            }
            try (Child a = new Child(root.resolve("a"))) {
                a.call("select", obj("peerId", bid)); JsonObject state = a.snapshot();
                check(string(state, "localId").equals(aid), "EOF must release profile lock and preserve identity");
                check(string(state, "draft").equals("restart draft"), "Draft must persist across child restart");
                check(string(state, "theme").equals("dark") && state.get("fontSize").getAsInt() == 1, "Settings must persist across child restart");
                check(!state.getAsJsonArray("history").get(0).getAsJsonObject().get("trusted").getAsBoolean(), "Revoke must persist across restart");
                a.fails("unsupportedOperation", obj());
            }
            String largePeer = BridgeFixtures.seedLarge(root.resolve("large"));
            try (Child large = new Child(root.resolve("large"))) {
                large.call("select", obj("peerId", largePeer));
                JsonObject initial = large.snapshot();
                check(initial.get("messagesHasMore").getAsBoolean(), "Oversized histories require explicit pagination");
                JsonObject search = large.call("messages", obj("peerId", largePeer, "query", "EARLY-ONLY-NEEDLE")).getAsJsonObject();
                check(search.getAsJsonArray("messages").size() == 1, "Search must include matching text older than the newest page, excluding unrelated messages");
                check(string(search.getAsJsonArray("messages").get(0).getAsJsonObject(), "body").startsWith("early-only-needle:"), "Search must retain the complete matching body");
                JsonObject queryPage = large.call("messages", obj("peerId", largePeer, "query", ":", "limit", 3)).getAsJsonObject();
                check(queryPage.getAsJsonArray("messages").size() == 3 && queryPage.get("hasMore").getAsBoolean(), "Search results must retain bounded pagination");
                Set<String> queryIds = new HashSet<>();
                for (JsonElement message : queryPage.getAsJsonArray("messages")) queryIds.add(string(message.getAsJsonObject(), "id"));
                JsonObject nextQueryPage = large.call("messages", obj("peerId", largePeer, "query", ":", "before", string(queryPage, "nextBefore"), "limit", 3)).getAsJsonObject();
                for (JsonElement message : nextQueryPage.getAsJsonArray("messages")) check(queryIds.add(string(message.getAsJsonObject(), "id")), "Search cursors must advance without duplicate results");
                Set<String> seen = new HashSet<>();
                for (JsonElement message : initial.getAsJsonArray("messages")) seen.add(string(message.getAsJsonObject(), "id"));
                String before = string(initial, "messagesBefore");
                boolean more = true;
                while (more) {
                    JsonObject page = large.call("messages", obj("peerId", largePeer, "before", before, "limit", 20)).getAsJsonObject();
                    check(!page.getAsJsonArray("messages").isEmpty(), "A history page must make progress");
                    for (JsonElement message : page.getAsJsonArray("messages")) check(seen.add(string(message.getAsJsonObject(), "id")), "Pagination must not duplicate messages");
                    more = page.get("hasMore").getAsBoolean();
                    if (more) before = string(page, "nextBefore");
                }
                check(seen.size() == 100, "Every retained message must remain accessible, without truncating bodies");
            }
            System.out.println("BridgeIntegrationTests: " + checks + " checks passed (real JSONL children, Noise trust/text/file/QR/read/EOF/session boundaries)");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
