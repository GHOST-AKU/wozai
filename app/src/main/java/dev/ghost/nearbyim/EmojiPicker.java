package dev.ghost.nearbyim;

import android.app.*;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.text.*;
import dev.ghost.nearbyim.core.EmojiCatalog;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Native recycled cells with system glyphs; insertion never sends a message. */
final class EmojiPicker {
    private static CompletableFuture<EmojiCatalog> data;
    private static synchronized CompletableFuture<EmojiCatalog> data(Context context){
        if(data==null){Context app=context.getApplicationContext();data=CompletableFuture.supplyAsync(()->{try{return new EmojiCatalog(app.getResources().openRawResource(R.raw.emoji_catalog));}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}});}return data;
    }
    private final Activity activity;
    private final EditText composer,query;
    private final Spinner categories;
    private final GridView grid;
    private final TextView empty;
    private final Function<String,String> text;
    private final BooleanSupplier allowed;
    private final android.content.SharedPreferences preferences;
    private final AlertDialog dialog;
    private EmojiCatalog catalog;
    private String recent;
    private List<EmojiCatalog.Entry> entries=List.of();
    private final BaseAdapter adapter=new BaseAdapter(){
        public int getCount(){return entries.size();}public Object getItem(int position){return entries.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){TextView cell=recycled instanceof TextView?(TextView)recycled:new TextView(activity);var entry=entries.get(position);cell.setText(entry.text);cell.setTextSize(28);cell.setGravity(Gravity.CENTER);cell.setMinHeight(dp(56));cell.setContentDescription(entry.label(AppLanguage.locale(activity)));cell.setBackgroundResource(android.R.drawable.list_selector_background);cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(56)));return cell;}
    };
    EmojiPicker(Activity activity,EditText composer,AlertDialog.Builder builder,Function<String,String> text,BooleanSupplier allowed){
        this.activity=activity;this.composer=composer;this.text=text;this.allowed=allowed;preferences=activity.getSharedPreferences("ui",Context.MODE_PRIVATE);recent=preferences.getString("emojiRecent","");
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(4),dp(12),dp(4));
        categories=new Spinner(activity);List<String> names=new ArrayList<>();names.add(text.apply("emojiRecent"));for(int i=0;i<9;i++)names.add(text.apply("emojiCategory"+i));categories.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));categories.setContentDescription(text.apply("emojiCategories"));panel.addView(categories);
        LinearLayout search=new LinearLayout(activity);query=new EditText(activity);query.setSingleLine(true);query.setHint(text.apply("emojiSearch"));query.setContentDescription(text.apply("emojiSearch"));search.addView(query,new LinearLayout.LayoutParams(0,dp(48),1));Button clear=new Button(activity);clear.setText(text.apply("emojiClear"));clear.setOnClickListener(v->query.setText(""));search.addView(clear);panel.addView(search);
        FrameLayout viewport=new FrameLayout(activity);grid=new GridView(activity);grid.setNumColumns(GridView.AUTO_FIT);grid.setColumnWidth(dp(56));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setAdapter(adapter);grid.setContentDescription(text.apply("emoji"));empty=new TextView(activity);empty.setGravity(Gravity.CENTER);empty.setText(text.apply("emojiNoResults"));viewport.addView(grid,new FrameLayout.LayoutParams(-1,-1));viewport.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);panel.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        panel.setLayoutParams(new ViewGroup.LayoutParams(-1,-1));
        TextView hint=new TextView(activity);hint.setText(text.apply("emojiVariantHint"));panel.addView(hint);
        dialog=builder.setTitle(text.apply("emoji")).setView(panel).setPositiveButton(text.apply("close"),(d,w)->{}).create();
        dialog.setOnDismissListener(d->composer.requestFocus());grid.setOnItemClickListener((parent,view,position,id)->pick(entries.get(position)));grid.setOnItemLongClickListener((parent,view,position,id)->{if(catalog==null)return true;PopupMenu variants=new PopupMenu(activity,view);for(var entry:catalog.variants(entries.get(position))){variants.getMenu().add(entry.text+" "+entry.label(AppLanguage.locale(activity))).setOnMenuItemClickListener(item->{pick(entry);return true;});}variants.show();return true;});
        categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){refresh();}public void onNothingSelected(AdapterView<?> p){}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}public void afterTextChanged(Editable e){}});
    }
    void show(){dialog.show();dialog.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels-dp(24),dp(560)),Math.min(dp(560),(int)(activity.getResources().getDisplayMetrics().heightPixels*.90)));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);data(activity).whenComplete((value,error)->activity.runOnUiThread(()->{if(activity.isDestroyed()||!dialog.isShowing())return;if(error!=null){empty.setText(text.apply("emojiLoadFailed"));return;}catalog=value;refresh();}));}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void refresh(){if(catalog==null)return;int i=categories.getSelectedItemPosition();entries=catalog.browse(i<=0?"recent":EmojiCatalog.CATEGORIES.get(i-1),query.getText().toString(),AppLanguage.locale(activity),recent);adapter.notifyDataSetChanged();}
    private void pick(EmojiCatalog.Entry entry){if(!allowed.getAsBoolean()){dialog.dismiss();return;}int a=composer.getSelectionStart(),b=composer.getSelectionEnd();if(a<0||b<0)a=b=composer.length();composer.getText().replace(Math.min(a,b),Math.max(a,b),entry.text);recent=catalog.remember(recent,entry);preferences.edit().putString("emojiRecent",recent).apply();}
}
