package dev.ghost.nearbyim;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.AttachmentBatchQueue;
import java.util.*;
import java.util.concurrent.*;

/** Immutable-on-confirm ordered preview; no source descriptor stays open on this page. */
public final class AttachmentSelectionActivity extends Activity {
    private record Metadata(String name,long size){}
    private final ArrayList<Uri> selected=new ArrayList<>();private final Map<Uri,Metadata> metadata=new HashMap<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"selection-metadata");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private SelectionThumbnails thumbnails;private Adapter adapter;private TextView count;private Button send;private ListView list;private int focused;
    protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    public void onCreate(Bundle saved){super.onCreate(saved);ArrayList<Uri> input=saved==null?getIntent().getParcelableArrayListExtra(AttachmentSelection.URIS):saved.getParcelableArrayList(AttachmentSelection.URIS);
        try{if(input==null||input.isEmpty()||input.size()>AttachmentBatchQueue.MAX_PENDING)throw new IllegalArgumentException();selected.addAll(AttachmentSelection.from(AttachmentSelection.result(input)));}catch(IllegalArgumentException invalid){finish();return;}focused=saved==null?0:saved.getInt("focused",0);thumbnails=new SelectionThumbnails(getContentResolver());
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(0xfff7faf7);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(12)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(12)+insets.getSystemWindowInsetRight(),dp(12)+insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout toolbar=new LinearLayout(this);Button cancel=button("cancel",this::finish);toolbar.addView(cancel);TextView title=new TextView(this);title.setText(text("attachmentPreview"));title.setTextSize(20);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);count=new TextView(this);root.addView(count);
        list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);adapter=new Adapter();list.setAdapter(adapter);list.setOnItemClickListener((p,v,index,id)->{focused=index;adapter.notifyDataSetChanged();});list.setRecyclerListener(view->{Holder holder=(Holder)view.getTag();if(holder!=null)holder.unbind();});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);controls.addView(button("attachmentMoveUp",()->move(-1)),new LinearLayout.LayoutParams(0,-2,1));controls.addView(button("attachmentMoveDown",()->move(1)),new LinearLayout.LayoutParams(0,-2,1));controls.addView(button("attachmentRemove",()->{if(!selected.isEmpty()){selected.remove(focused);focused=Math.max(0,Math.min(focused,selected.size()-1));update();}}),new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);
        send=button("send",()->{if(!selected.isEmpty()){setResult(RESULT_OK,AttachmentSelection.result(List.copyOf(selected)));finish();}});send.setCompoundDrawablesWithIntrinsicBounds(R.drawable.outline_send_24,0,0,0);root.addView(send);setContentView(root);update();
        List<Uri> pending=List.copyOf(selected);worker.execute(()->{Map<Uri,Metadata> found=new HashMap<>();for(Uri uri:pending){if(Thread.currentThread().isInterrupted())break;String name=uri.getLastPathSegment();long size=-1;
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s);}}catch(Exception ignored){}found.put(uri,new Metadata(name==null?"attachment":name,size));}
            runOnUiThread(()->{if(!isDestroyed()){metadata.putAll(found);adapter.notifyDataSetChanged();}});
        });
    }
    private String text(String key,Object...args){return AndroidText.get(this,key,args);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button button(String key,Runnable action){Button button=new Button(this);button.setText(text(key));button.setOnClickListener(v->action.run());return button;}
    private void move(int by){int target=focused+by;if(focused<0||target<0||target>=selected.size())return;Uri uri=selected.remove(focused);selected.add(target,uri);focused=target;update();list.smoothScrollToPosition(focused);}
    private void update(){count.setText(text("attachmentSelectionCount",selected.size()));send.setEnabled(!selected.isEmpty());adapter.notifyDataSetChanged();}
    protected void onSaveInstanceState(Bundle state){state.putParcelableArrayList(AttachmentSelection.URIS,selected);state.putInt("focused",focused);super.onSaveInstanceState(state);}
    protected void onDestroy(){if(thumbnails!=null)thumbnails.close();worker.shutdownNow();super.onDestroy();}
    private final class Holder {final ImageView image;final TextView label;Uri uri;AutoCloseable subscription;Holder(ImageView image,TextView label){this.image=image;this.label=label;}void unbind(){if(subscription!=null)try{subscription.close();}catch(Exception ignored){}subscription=null;uri=null;}}
    private final class Adapter extends BaseAdapter {
        public int getCount(){return selected.size();}public Object getItem(int i){return selected.get(i);}public long getItemId(int i){return selected.get(i).toString().hashCode();}
        public View getView(int i,View view,ViewGroup parent){Holder h;if(view==null){LinearLayout row=new LinearLayout(AttachmentSelectionActivity.this);row.setPadding(dp(8),dp(8),dp(8),dp(8));row.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(AttachmentSelectionActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(image,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView name=new TextView(AttachmentSelectionActivity.this);name.setTextColor(Color.BLACK);name.setTextSize(15);name.setMaxLines(3);name.setPadding(dp(12),0,0,0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));h=new Holder(image,name);row.setTag(h);view=row;}else h=(Holder)view.getTag();
            h.unbind();Uri uri=selected.get(i);h.uri=uri;Metadata m=metadata.get(uri);String name=m==null?uri.getLastPathSegment():m.name;String size=m==null||m.size<0?text("attachmentUnknownSize"):android.text.format.Formatter.formatShortFileSize(AttachmentSelectionActivity.this,m.size);h.label.setText((i+1)+". "+name+"\n"+size);view.setBackgroundColor(i==focused?0xffdef1e5:Color.TRANSPARENT);h.image.setImageResource(R.drawable.outline_description_24);h.subscription=thumbnails.bind(uri,bitmap->{if(uri.equals(h.uri)&&bitmap!=null)h.image.setImageBitmap(bitmap);});return view;
        }
    }
}
