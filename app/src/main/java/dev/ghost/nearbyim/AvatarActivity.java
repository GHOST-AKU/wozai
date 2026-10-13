package dev.ghost.nearbyim;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.*;
import java.io.IOException;
import java.util.concurrent.*;

/** The local device's avatar editor. Nothing is persisted until Save/default is selected. */
public final class AvatarActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> new Thread(task, "avatar-editor"));
    private Future<?> task;
    private CancellationSignal selectionCancellation;
    private volatile int generation;
    private AvatarStore store;
    private String identityRoot;
    private long revision;
    private boolean ready, saving;
    private Bitmap source, finalImage;
    private byte[] finalPng;
    private Uri selection;
    private RectF restoredCrop;
    private AvatarCropView crop;
    private AvatarView current;
    private ImageView preview;
    private TextView notice, stage;
    private LinearLayout cropActions;
    private Button choose, reset, useCrop, adjust, save;
    private int ink, muted, background, surface, accent;
    protected void attachBaseContext(Context context) { super.attachBaseContext(AppLanguage.wrap(context)); }
    public void onCreate(Bundle state) {
        super.onCreate(state);if(state!=null){String uri=state.getString("avatarSelection");selection=uri==null?null:Uri.parse(uri);float[] square=state.getFloatArray("avatarCrop");if(square!=null&&square.length==4)restoredCrop=new RectF(square[0],square[1],square[2],square[3]);} palette(); build(); load();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::cancel);
    }
    private void palette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? 0xff101619 : 0xfffafcfa; surface = dark ? 0xff222b2f : 0xffeef3ef;
        ink = dark ? 0xfff1f5f3 : 0xff17211c; muted = dark ? 0xff9aa8ad : 0xff58675f; accent = dark ? 0xff82d8ba : 0xff246b4e;
    }
    private void build() {
        LinearLayout root = vertical(); root.setPadding(dp(16), dp(8), dp(16), dp(16)); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) { Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom); }
            else view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(16) + insets.getSystemWindowInsetBottom()); return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        boolean light = background == 0xfffafcfa;
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) getWindow().getInsetsController().setSystemBarsAppearance(light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        else getWindow().getDecorView().setSystemUiVisibility(light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        LinearLayout toolbar = horizontal(); TextView title = label(text("avatarTitle"), 22, ink); title.setTypeface(null, android.graphics.Typeface.BOLD); toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); toolbar.addView(button("cancel", this::cancel)); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout body = vertical(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = label(text("avatarVisibilityHint"), 14, muted); body.addView(hint);
        current = new AvatarView(this); LinearLayout.LayoutParams currentSize = new LinearLayout.LayoutParams(dp(88), dp(88)); currentSize.gravity = Gravity.CENTER_HORIZONTAL; currentSize.topMargin = dp(12); body.addView(current, currentSize);
        choose = button("avatarChoose", this::pick); choose.setTag("avatarChoose"); body.addView(choose); reset = button("avatarDefault", this::resetDefault); reset.setTag("avatarDefault"); body.addView(reset);
        stage = label(text("avatarCrop"), 18, ink); stage.setVisibility(View.GONE); body.addView(stage);
        crop = new AvatarCropView(this); crop.setTag("avatarCrop"); crop.setVisibility(View.GONE); body.addView(crop, new LinearLayout.LayoutParams(-1, dp(280)));
        cropActions = horizontal(); cropActions.setGravity(Gravity.CENTER);
        cropActions.addView(button("photoZoomOut", () -> crop.zoom(.8f)), new LinearLayout.LayoutParams(0, -2, 1));
        cropActions.addView(button("avatarResetCrop", crop::reset), new LinearLayout.LayoutParams(0, -2, 1));
        cropActions.addView(button("photoZoomIn", () -> crop.zoom(1.25f)), new LinearLayout.LayoutParams(0, -2, 1)); cropActions.setVisibility(View.GONE); body.addView(cropActions);
        useCrop = button("avatarUseCrop", this::prepare); useCrop.setTag("avatarUseCrop"); useCrop.setVisibility(View.GONE); body.addView(useCrop);
        preview = new ImageView(this); preview.setTag("avatarPreview"); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription(text("avatarPreview")); preview.setVisibility(View.GONE); body.addView(preview, new LinearLayout.LayoutParams(-1, dp(256)));
        adjust = button("avatarEditCrop", this::editCrop); adjust.setVisibility(View.GONE); body.addView(adjust);
        save = button("avatarSave", this::save); save.setTag("avatarSave"); save.setVisibility(View.GONE); body.addView(save);
        notice = label(text("loading"), 14, muted); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); body.addView(notice); controls(); setContentView(root); root.requestApplyInsets();
    }
    private void load() {
        submit(() -> {
            String root = AndroidIdentity.load().publicKey(); AvatarStore loaded = new AvatarStore(getFilesDir().toPath().resolve("avatars"), root); AvatarSnapshot snapshot = loaded.snapshot();
            return () -> { identityRoot = root; store = loaded; revision = snapshot.profile().revision; ready = true; AndroidAvatars.get(this).bind(current, root); notice.setText(text("avatarSavedOnDevice")); controls();if(selection!=null)decodeSelection(); };
        });
    }
    private void pick() {
        if (!ready || saving) return; invalidateTask();controls();
        Intent intent = Build.VERSION.SDK_INT >= 33 ? new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*") : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(intent, 1); } catch (ActivityNotFoundException failure) { notice.setText(text("avatarUnavailable")); }
    }
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);if(request!=1||saving)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){notice.setText(text(preview.getVisibility()==View.VISIBLE?"avatarPreview":source!=null?"avatarCropHint":"avatarSavedOnDevice"));controls();return;}
        selection=data.getData();restoredCrop=null;if(ready)decodeSelection();
    }
    private void decodeSelection() {
        Uri chosen=selection;invalidateTask(); clearImages(); notice.setText(text("loading")); controls();
        CancellationSignal cancellation=new CancellationSignal();selectionCancellation=cancellation;
        int token = generation;
        try { task = worker.submit(() -> {
            try {
                Bitmap decoded = AvatarImages.decode(getContentResolver(), chosen,cancellation);
                runOnUiThread(() -> { if (!active(token)) { decoded.recycle(); return; } source = decoded; crop.image(decoded);if(restoredCrop!=null){crop.restore(restoredCrop);restoredCrop=null;} editCrop(); notice.setText(text("avatarCropHint")); controls(); });
            } catch (Exception | OutOfMemoryError failure) { failed(token, failure); }
        }); } catch (RejectedExecutionException busy) { notice.setText(text("avatarUnavailable")); }
    }
    private void editCrop() {
        if (source == null || saving) return;
        stage.setText(text("avatarCrop")); stage.setVisibility(View.VISIBLE); crop.setVisibility(View.VISIBLE); cropActions.setVisibility(View.VISIBLE); useCrop.setVisibility(View.VISIBLE);
        preview.setVisibility(View.GONE); adjust.setVisibility(View.GONE); save.setVisibility(View.GONE); notice.setText(text("avatarCropHint")); controls();
    }
    private void prepare() {
        if (source == null || saving) return; RectF square = crop.crop(); Bitmap input = source; notice.setText(text("loading")); invalidateTask(); int token = generation; useCrop.setEnabled(false);
        try { task = worker.submit(() -> {
            try {
                byte[] bytes = AvatarImages.encodeCrop(input, square); Bitmap decoded = AvatarImages.preview(bytes);
                runOnUiThread(() -> {
                    if (!active(token)) { decoded.recycle(); return; }
                    preview.setImageDrawable(null); if (finalImage != null) finalImage.recycle(); finalImage = decoded; finalPng = bytes; preview.setImageBitmap(decoded);
                    stage.setText(text("avatarPreview")); crop.setVisibility(View.GONE); cropActions.setVisibility(View.GONE); useCrop.setVisibility(View.GONE); preview.setVisibility(View.VISIBLE); adjust.setVisibility(View.VISIBLE); save.setVisibility(View.VISIBLE); notice.setText(text("avatarPreview")); controls();
                });
            } catch (Exception | OutOfMemoryError failure) { failed(token, failure); }
        }); } catch (RejectedExecutionException busy) { notice.setText(text("avatarUnavailable")); controls(); }
    }
    private void save() { if (ready && finalPng != null && !saving) persist(finalPng); }
    private void resetDefault() { if (ready && !saving) persist(null); }
    private void persist(byte[] png) {
        invalidateTask(); saving = true; controls(); notice.setText(text("loading")); long expected = revision; AvatarStore target = store; String root = identityRoot;
        submit(() -> {
            if(target.snapshot().profile().revision!=expected)throw new java.util.ConcurrentModificationException("Avatar changed during editing");
            try{if (png == null) target.reset(expected); else target.save(expected, png);}
            catch(IOException failure){if(target.snapshot().profile().revision!=expected)throw new java.util.ConcurrentModificationException("Avatar changed during editing");throw failure;}
            AndroidAvatars.get(this).committed(root);
            return () -> { Toast.makeText(this, text("avatarSaved"), Toast.LENGTH_SHORT).show(); setResult(RESULT_OK); finish(); };
        });
    }
    private interface Work { Runnable run() throws Exception; }
    private void submit(Work work) {
        int token = generation;
        try { task = worker.submit(() -> {
            try { if (!active(token)) return; Runnable result = work.run(); runOnUiThread(() -> { if (active(token)) result.run(); }); }
            catch (Exception | OutOfMemoryError failure) { failed(token, failure); }
        }); } catch (RejectedExecutionException failure) { failed(token, failure); }
    }
    private boolean active(int token) { return token == generation && !isFinishing() && !isDestroyed() && !Thread.currentThread().isInterrupted(); }
    private void failed(int token, Throwable failure) {
        runOnUiThread(() -> { if (!active(token)) return; saving = false; notice.setText(text(failure instanceof java.util.ConcurrentModificationException ? "avatarChanged" : "avatarUnavailable")); controls(); });
    }
    private void controls() { choose.setEnabled(ready && !saving); reset.setEnabled(ready && !saving); useCrop.setEnabled(source != null && !saving); save.setEnabled(finalPng != null && !saving); adjust.setEnabled(!saving); cropActions.setEnabled(!saving); }
    private void invalidateTask() { generation++; if (task != null) task.cancel(true);CancellationSignal signal=selectionCancellation;selectionCancellation=null;if(signal!=null){Thread cancel=new Thread(signal::cancel,"avatar-cancel");cancel.setDaemon(true);cancel.start();} worker.purge(); }
    private void clearImages() {
        crop.image(null); preview.setImageDrawable(null); Bitmap oldSource = source, oldFinal = finalImage; source = null; finalImage = null; finalPng = null;
        crop.setVisibility(View.GONE); cropActions.setVisibility(View.GONE); useCrop.setVisibility(View.GONE); preview.setVisibility(View.GONE); adjust.setVisibility(View.GONE); save.setVisibility(View.GONE); stage.setVisibility(View.GONE);
        try { worker.execute(() -> { if (oldSource != null) oldSource.recycle(); if (oldFinal != null && oldFinal != oldSource) oldFinal.recycle(); }); }
        catch (RejectedExecutionException saturated) { /* Detached bitmaps remain eligible for normal reclamation. */ }
    }
    private void cancel() { if (saving) return; invalidateTask(); setResult(RESULT_CANCELED); finish(); }
    @SuppressLint("GestureBackNavigation") @SuppressWarnings("deprecation")
    public void onBackPressed() { if(Build.VERSION.SDK_INT<33)cancel();else super.onBackPressed(); }
    protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state);if(selection!=null)state.putString("avatarSelection",selection.toString());RectF square=crop.crop();if(square!=null)state.putFloatArray("avatarCrop",new float[]{square.left,square.top,square.right,square.bottom});}
    protected void onDestroy() { invalidateTask(); clearImages(); worker.shutdown(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String text(String key) { return AndroidText.get(this, key); }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(8), 0, dp(8)); return view; }
    private Button button(String key, Runnable action) { Button view = new Button(this); view.setText(text(key)); view.setAllCaps(false); view.setTextColor(ink); view.setTextSize(14); view.setMinHeight(dp(48)); view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface)); view.setOnClickListener(clicked -> action.run()); return view; }
}
