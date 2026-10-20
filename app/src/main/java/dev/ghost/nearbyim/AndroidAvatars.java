package dev.ghost.nearbyim;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import dev.ghost.nearbyim.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-local cache and notifications; all decoding, hashing and storage run off the UI thread. */
public final class AndroidAvatars {
    public interface Listener { void changed(String canonicalRoot); }
    private static final int MAX_CACHE_BYTES = 2 * 1024 * 1024;
    private static AndroidAvatars instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> { Thread thread = new Thread(task, "avatars"); thread.setDaemon(true); return thread; });
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final WeakHashMap<AvatarView, String> views = new WeakHashMap<>();
    private final Map<String, Long> generations = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private long serial;
    private int cachedBytes;
    private static final class Entry {
        final Identicon.Model model; final Bitmap image; final int bytes;
        Entry(Identicon.Model model, Bitmap image) { this.model = model; this.image = image; bytes = 256 + (image == null ? 0 : image.getAllocationByteCount()); }
    }
    private AndroidAvatars(Context context) { this.context = context.getApplicationContext(); }
    public static synchronized AndroidAvatars get(Context context) { if (instance == null) instance = new AndroidAvatars(context); return instance; }
    public void listen(Listener listener) { listeners.addIfAbsent(listener); }
    public void unlisten(Listener listener) { listeners.remove(listener); }
    /** root is supplied only by the controller's authenticated/trusted identity lookup. */
    public void bind(AvatarView view, String root) {
        view.avatarRoot = root; view.avatarGeneration++; views.put(view, root);
        if (root == null) { view.display(Identicon.neutral(), null); return; }
        Entry entry = cache.get(root); view.display(entry == null ? Identicon.neutral() : entry.model, entry == null ? null : entry.image);
        if (entry == null && !loading.contains(root)) request(root);
    }
    /** Reloads only matching views after an authenticated received avatar commit. */
    public void refresh(String canonicalRoot) {
        if (canonicalRoot == null) return;
        main.post(() -> { remove(canonicalRoot); loading.remove(canonicalRoot); request(canonicalRoot); });
    }
    /** Called only after a successful local save/reset transaction. */
    public void committed(String canonicalRoot) {
        refresh(canonicalRoot);
        main.post(() -> { for (Listener listener : listeners) listener.changed(canonicalRoot); });
    }
    private void request(String root) {
        long generation = ++serial; generations.put(root, generation); loading.add(root);
        try { worker.execute(() -> {
            Identicon.Model model = Identicon.neutral(); Bitmap bitmap = null;
            try {
                model = Identicon.fromPublicKey(root);
                AvatarSnapshot snapshot = new AvatarStore(context.getFilesDir().getCanonicalFile().toPath().resolve("avatars"), root).snapshot();
                byte[] png = snapshot.png(); if (png.length != 0) bitmap = AvatarImages.preview(png);
            } catch (Exception | OutOfMemoryError unavailable) { /* A missing/corrupt avatar cannot affect chat. */ }
            Entry entry = new Entry(model, bitmap);
            main.post(() -> {
                if (!Objects.equals(generations.get(root), generation)) { if (entry.image != null) entry.image.recycle(); return; }
                loading.remove(root); generations.remove(root); remove(root); cache.put(root, entry); cachedBytes += entry.bytes;
                update(root, entry);
                while (cachedBytes > MAX_CACHE_BYTES || cache.size() > 128) remove(cache.keySet().iterator().next());
            });
        }); } catch (RejectedExecutionException unavailable) { loading.remove(root); generations.remove(root); }
    }
    private void update(String root, Entry entry) {
        for (Map.Entry<AvatarView, String> binding : views.entrySet()) if (Objects.equals(root, binding.getValue())) binding.getKey().display(entry.model, entry.image);
    }
    private void remove(String root) {
        Entry old = cache.remove(root); if (old == null) return; cachedBytes -= old.bytes;
        update(root, new Entry(old.model, null)); if (old.image != null) old.image.recycle();
    }
}
