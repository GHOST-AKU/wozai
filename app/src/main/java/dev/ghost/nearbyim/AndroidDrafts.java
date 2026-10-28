package dev.ghost.nearbyim;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.ghost.nearbyim.core.DraftStore;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One application-scoped writer keeps draft commits ordered across Activity recreation. */
final class AndroidDrafts {
    private static AndroidDrafts instance;
    static synchronized AndroidDrafts get(Context context) {
        if (instance == null) instance = new AndroidDrafts(context.getApplicationContext());
        return instance;
    }

    private record Write(String text, Consumer<IOException> completed) { }
    private final File files;
    private DraftStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "draft-storage");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, String> cache = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Write> pending = new LinkedHashMap<>();
    private final Map<String, IOException> failures = new HashMap<>();
    private boolean writing;

    AndroidDrafts(Context context) { files = context.getFilesDir(); }

    private DraftStore store() throws IOException {
        // Resolve only the framework-provided root (for example /data/user/0).
        // The draft subdirectory still goes through PrivateMetadata's no-symlink checks.
        if (store == null) store = new DraftStore(files.getCanonicalFile().toPath().resolve("drafts-v1"));
        return store;
    }

    synchronized String cached(String peer) { return cache.get(peer); }
    synchronized IOException failure(String peer) { return failures.get(peer); }

    void load(String peer, BiConsumer<String, IOException> result) {
        worker.execute(() -> {
            String text;
            IOException failure = null;
            try {
                synchronized (this) { text = cache.get(peer); }
                if (text == null) text = store().load(peer);
                if (text == null) text = "";
                synchronized (this) {
                    if (cache.containsKey(peer)) text = cache.get(peer);
                    else { cache.put(peer, text); trim(); }
                }
            } catch (IOException error) { text = ""; failure = error; }
            String value = text;
            IOException error = failure;
            main.post(() -> result.accept(value, error));
        });
    }

    void clearIfUnchanged(String peer, String sent, Consumer<IOException> completed) {
        worker.execute(() -> {
            try {
                String saved = store().load(peer);
                synchronized (this) {
                    String latest = cache.containsKey(peer) ? cache.get(peer) : saved;
                    if (Objects.equals(latest, sent)) save(peer, "", completed);
                }
            } catch (IOException error) {
                synchronized (this) { failures.put(peer, error); }
                main.post(() -> completed.accept(error));
            }
        });
    }

    /** Coalesces each peer's pending write; null completion means an actual disk commit. */
    synchronized void save(String peer, String text, Consumer<IOException> completed) {
        if (!pending.containsKey(peer) && pending.size() >= 32) {
            IOException error = new IOException("Draft writer is busy");
            failures.put(peer, error);
            main.post(() -> completed.accept(error));
            return;
        }
        cache.put(peer, text);
        pending.put(peer, new Write(text, completed));
        trim();
        if (writing) return;
        writing = true;
        worker.execute(this::writePending);
    }

    private void writePending() {
        while (true) {
            String peer;
            Write write;
            synchronized (this) {
                if (pending.isEmpty()) { writing = false; trim(); return; }
                peer = pending.keySet().iterator().next();
                write = pending.remove(peer);
            }
            IOException failure = null;
            try { store().save(peer, write.text()); }
            catch (IOException error) { failure = error; }
            synchronized (this) {
                if (failure == null) failures.remove(peer);
                else failures.put(peer, failure);
            }
            IOException error = failure;
            main.post(() -> write.completed().accept(error));
        }
    }

    private void trim() {
        Iterator<String> keys = cache.keySet().iterator();
        while (cache.size() > 16 && keys.hasNext()) {
            String peer = keys.next();
            if (!pending.containsKey(peer)) { keys.remove(); failures.remove(peer); }
        }
    }
}
