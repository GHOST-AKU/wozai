package dev.ghost.nearbyim;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import dev.ghost.nearbyim.core.EmojiCatalog;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** A composer dock with recycled native rows and system-rendered Unicode emoji. */
final class EmojiPicker {
    private static CompletableFuture<EmojiCatalog> data;
    private static synchronized CompletableFuture<EmojiCatalog> data(Context context) {
        if (data == null) {
            Context app = context.getApplicationContext();
            data = CompletableFuture.supplyAsync(() -> {
                try { return new EmojiCatalog(app.getResources().openRawResource(R.raw.emoji_catalog)); }
                catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
            });
        }
        return data;
    }

    private record Row(String heading, List<EmojiCatalog.Entry> entries) { }
    private static final int[] ICONS = {
        R.drawable.outline_history_24, R.drawable.outline_emoji_emotions_24,
        R.drawable.outline_front_hand_24, R.drawable.outline_pets_24,
        R.drawable.outline_restaurant_24, R.drawable.outline_directions_car_24,
        R.drawable.outline_sports_soccer_24, R.drawable.outline_lightbulb_24,
        R.drawable.outline_tag_24, R.drawable.outline_flag_24
    };
    private final Activity activity;
    private final EditText composer, query;
    private final LinearLayout host, root;
    private final ListView list;
    private final TextView title, empty;
    private final ImageButton clear;
    private final HorizontalScrollView categoryBar;
    private final List<ImageButton> tabs = new ArrayList<>();
    private final Function<String, String> text;
    private final BooleanSupplier allowed;
    private final Runnable closed;
    private final android.content.SharedPreferences preferences;
    private final int surface, ink, muted, accent, tonal;
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::adjustLayout;
    private EmojiCatalog catalog;
    private String recent;
    private int category, columns = 1;
    private List<Row> rows = List.of();

    EmojiPicker(Activity activity, EditText composer, LinearLayout host,
                Function<String, String> text, BooleanSupplier allowed, Runnable closed,
                int background, int surface, int ink, int muted, int accent, int tonal, int line) {
        this.activity = activity;
        this.composer = composer;
        this.host = host;
        this.text = text;
        this.allowed = allowed;
        this.closed = closed;
        this.surface = surface; this.ink = ink; this.muted = muted;
        this.accent = accent; this.tonal = tonal;
        preferences = activity.getSharedPreferences("ui", Context.MODE_PRIVATE);
        recent = preferences.getString("emojiRecent", "");

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag("emoji.panel");
        GradientDrawable panel = rounded(background, 0);
        panel.setCornerRadii(new float[]{dp(20),dp(20),dp(20),dp(20),0,0,0,0});
        panel.setStroke(dp(1), line);
        root.setBackground(panel);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton search = icon(R.drawable.outline_search_24, text.apply("emojiSearch"));
        search.setOnClickListener(view -> toggleSearch());
        header.addView(search, new LinearLayout.LayoutParams(dp(48), dp(48)));
        FrameLayout center = new FrameLayout(activity);
        title = new TextView(activity);
        title.setText(text.apply("emoji"));
        title.setTextColor(ink);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        center.addView(title, new FrameLayout.LayoutParams(-1, -1));
        query = new EditText(activity);
        query.setTag("emoji.search");
        query.setSingleLine(true);
        query.setTextSize(15);
        query.setTextColor(ink);
        query.setHintTextColor(muted);
        query.setHint(text.apply("emojiSearch"));
        query.setContentDescription(text.apply("emojiSearch"));
        query.setMinWidth(0);
        query.setMinimumWidth(0);
        query.setPadding(dp(12), 0, dp(8), 0);
        query.setBackground(rounded(surface, dp(20)));
        query.setVisibility(View.GONE);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        FrameLayout.LayoutParams searchBounds = new FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER_VERTICAL);
        center.addView(query, searchBounds);
        header.addView(center, new LinearLayout.LayoutParams(0, dp(48), 1));
        clear = icon(R.drawable.outline_close_24, text.apply("emojiClear"));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(view -> query.setText(""));
        header.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton close = icon(R.drawable.outline_keyboard_arrow_down_24, text.apply("close"));
        close.setOnClickListener(view -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

        FrameLayout viewport = new FrameLayout(activity);
        list = new ListView(activity);
        list.setTag("emoji.rows");
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(android.R.color.transparent);
        list.setPadding(dp(8), 0, dp(8), dp(4));
        list.setClipToPadding(false);
        list.setItemsCanFocus(true);
        list.setContentDescription(text.apply("emoji"));
        list.setAdapter(adapter);
        empty = new TextView(activity);
        empty.setTextColor(muted);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        viewport.addView(list, new FrameLayout.LayoutParams(-1, -1));
        viewport.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));

        categoryBar = new HorizontalScrollView(activity);
        categoryBar.setTag("emoji.categories");
        categoryBar.setHorizontalScrollBarEnabled(false);
        categoryBar.setFillViewport(true);
        categoryBar.setBackgroundColor(surface);
        LinearLayout categories = new LinearLayout(activity);
        categories.setGravity(Gravity.CENTER_VERTICAL);
        categories.setPadding(dp(4), 0, dp(4), 0);
        for (int index = 0; index < ICONS.length; index++) {
            final int selected = index;
            ImageButton tab = icon(ICONS[index], categoryName(index));
            tab.setTag("emoji.category." + index);
            tab.setOnClickListener(view -> {
                category = selected;
                hideSearch();
                refresh();
                list.setSelection(0);
            });
            tabs.add(tab);
            categories.addView(tab, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        categoryBar.addView(categories, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(categoryBar, new LinearLayout.LayoutParams(-1, dp(48)));
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                clear.setVisibility(value.length() == 0 ? View.GONE : View.VISIBLE);
                refresh();
                list.setSelection(0);
            }
            public void afterTextChanged(Editable value) { }
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            keyboard().hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
            return true;
        });
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int position) { return rows.get(position).heading() == null ? 1 : 0; }
        public boolean areAllItemsEnabled() { return false; }
        public boolean isEnabled(int position) { return rows.get(position).heading() == null; }

        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.heading() != null) {
                TextView heading = recycled instanceof TextView ? (TextView) recycled : new TextView(activity);
                heading.setText(row.heading());
                heading.setTextSize(13);
                heading.setTextColor(muted);
                heading.setPadding(dp(8), dp(8), dp(8), dp(4));
                if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                return heading;
            }
            LinearLayout cells = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(activity);
            if (cells.getChildCount() != columns) {
                cells.removeAllViews();
                for (int index = 0; index < columns; index++) {
                    TextView cell = new TextView(activity);
                    cell.setGravity(Gravity.CENTER);
                    cell.setTextSize(28);
                    cell.setSingleLine(true);
                    cell.setFocusable(true);
                    cell.setBackground(new RippleDrawable(ColorStateList.valueOf(tonal), null, rounded(Color.WHITE, dp(18))));
                    cells.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
                }
            }
            cells.setLayoutParams(new AbsListView.LayoutParams(-1, cellHeight()));
            for (int index = 0; index < columns; index++) {
                TextView cell = (TextView) cells.getChildAt(index);
                if (index >= row.entries().size()) { cell.setVisibility(View.INVISIBLE); continue; }
                EmojiCatalog.Entry entry = row.entries().get(index);
                cell.setVisibility(View.VISIBLE);
                cell.setText(entry.text);
                cell.setTextColor(ink);
                cell.setTag(entry.text);
                cell.setContentDescription(entry.label(AppLanguage.locale(activity)));
                cell.setTooltipText(entry.label(AppLanguage.locale(activity)));
                cell.setOnClickListener(view -> pick(entry));
                cell.setOnLongClickListener(view -> variants(cell, entry));
            }
            return cells;
        }
    };

    void show() {
        host.addView(root, new LinearLayout.LayoutParams(-1, dp(280)));
        host.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        root.post(this::adjustLayout);
        data(activity).whenComplete((value, error) -> activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || !isShowing()) return;
            if (error != null) { empty.setText(text.apply("emojiLoadFailed")); return; }
            catalog = value;
            refresh();
        }));
    }

    boolean isShowing() { return root.getParent() == host; }

    void close() {
        if (!isShowing()) return;
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        keyboard().hideSoftInputFromWindow(query.getWindowToken(), 0);
        host.removeView(root);
        composer.requestFocus();
        closed.run();
    }

    private void adjustLayout() {
        if (!isShowing() || host.getHeight() <= 0) return;
        int fixed = host.getPaddingTop() + host.getPaddingBottom();
        for (int index = 0; index < host.getChildCount(); index++) {
            View child = host.getChildAt(index);
            if (child == root || child.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) child.getLayoutParams();
            if (bounds.weight == 0) fixed += child.getMeasuredHeight() + bounds.topMargin + bounds.bottomMargin;
        }
        int height = Math.min(dp(320), Math.min(Math.round(host.getHeight() * .45f), Math.max(0, host.getHeight() - fixed)));
        if (root.getLayoutParams().height != height) {
            root.getLayoutParams().height = height;
            root.requestLayout();
        }
        categoryBar.setVisibility(height < dp(128) ? View.GONE : View.VISIBLE);
        int available = Math.max(1, root.getWidth() - dp(16));
        int count = Math.max(1, available / Math.max(dp(48), cellHeight()));
        if (count != columns) { columns = count; refresh(); }
    }

    private void toggleSearch() {
        if (query.getVisibility() == View.VISIBLE) { hideSearch(); return; }
        title.setVisibility(View.GONE);
        query.setVisibility(View.VISIBLE);
        query.requestFocus();
        keyboard().showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideSearch() {
        query.setText("");
        query.clearFocus();
        query.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        keyboard().hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private InputMethodManager keyboard() { return (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int cellHeight() { return Math.max(dp(52), Math.round(28 * activity.getResources().getDisplayMetrics().scaledDensity) + dp(12)); }
    private String categoryName(int index) { return text.apply(index == 0 ? "emojiRecent" : "emojiCategory" + (index - 1)); }

    private ImageButton icon(int resource, String label) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(resource);
        button.setImageTintList(ColorStateList.valueOf(muted));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(tonal), null, rounded(Color.WHITE, dp(22))));
        return button;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void section(List<Row> target, String heading, List<EmojiCatalog.Entry> entries) {
        if (entries.isEmpty()) return;
        target.add(new Row(heading, List.of()));
        cells(target, entries);
    }

    private void cells(List<Row> target, List<EmojiCatalog.Entry> entries) {
        for (int index = 0; index < entries.size(); index += columns)
            target.add(new Row(null, entries.subList(index, Math.min(entries.size(), index + columns))));
    }

    private void refresh() {
        for (int index = 0; index < tabs.size(); index++) {
            ImageButton tab = tabs.get(index);
            tab.setSelected(index == category);
            tab.setImageTintList(ColorStateList.valueOf(index == category ? accent : muted));
            tab.setBackground(new RippleDrawable(ColorStateList.valueOf(tonal),
                    rounded(index == category ? tonal : Color.TRANSPARENT, dp(22)), null));
        }
        if (catalog == null) return;
        List<Row> next = new ArrayList<>();
        String search = query.getText().toString();
        if (!search.trim().isEmpty()) cells(next, catalog.browse("recent", search, AppLanguage.locale(activity), recent));
        else if (category == 0) {
            section(next, text.apply("emojiRecent"), catalog.recent(recent));
            section(next, text.apply("emojiCategory0"), catalog.browse(EmojiCatalog.CATEGORIES.get(0), "", AppLanguage.locale(activity), recent));
        } else section(next, categoryName(category), catalog.browse(EmojiCatalog.CATEGORIES.get(category - 1), "", AppLanguage.locale(activity), recent));
        rows = next;
        empty.setText(text.apply("emojiNoResults"));
        adapter.notifyDataSetChanged();
    }

    private boolean variants(View anchor, EmojiCatalog.Entry entry) {
        if (catalog == null || catalog.variants(entry).size() < 2) return false;
        PopupMenu variants = new PopupMenu(activity, anchor);
        for (EmojiCatalog.Entry choice : catalog.variants(entry)) {
            variants.getMenu().add(choice.text + " " + choice.label(AppLanguage.locale(activity))).setOnMenuItemClickListener(item -> {
                pick(choice);
                return true;
            });
        }
        variants.show();
        return true;
    }

    private void pick(EmojiCatalog.Entry entry) {
        if (!allowed.getAsBoolean()) { close(); return; }
        int a = composer.getSelectionStart(), b = composer.getSelectionEnd();
        if (a < 0 || b < 0) a = b = composer.length();
        int start = Math.min(a, b), end = Math.max(a, b);
        Editable editable = composer.getText();
        editable.replace(start, end, entry.text);
        int cursor = start + entry.text.length();
        if (cursor <= editable.length() && editable.subSequence(start, cursor).toString().equals(entry.text)) {
            composer.setSelection(cursor);
            recent = catalog.remember(recent, entry);
            preferences.edit().putString("emojiRecent", recent).apply();
        }
    }
}
