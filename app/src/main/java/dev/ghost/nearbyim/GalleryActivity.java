package dev.ghost.nearbyim;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.AttachmentBatchQueue;
import java.util.*;
import java.util.concurrent.*;

/** A paged, recycling MediaStore gallery for API 26 and devices without a system photo picker. */
public final class GalleryActivity extends Activity {
    private FoldWindow.SafeArea foldArea;
    private record Photo(long id,Uri uri,String name){}
    private final ArrayList<Photo> photos=new ArrayList<>();
    private final LinkedHashSet<Uri> selected=new LinkedHashSet<>();
    private final ThreadPoolExecutor query=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"gallery-query");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private SelectionThumbnails thumbnails;
    private GridView grid;private Adapter adapter;private TextView notice;private Button confirm,more,access;private boolean loading,exhausted;
    protected void onStart(){super.onStart();if(foldArea!=null)foldArea.start();}
    protected void onStop(){if(foldArea!=null)foldArea.stop();super.onStop();}
    protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    public void onCreate(Bundle saved){super.onCreate(saved);if(saved!=null){ArrayList<Uri> restored=saved.getParcelableArrayList("selected");if(restored!=null&&restored.size()<=AttachmentBatchQueue.MAX_PENDING)selected.addAll(restored);}thumbnails=new SelectionThumbnails(getContentResolver());
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff7faf7);root.setPadding(dp(12),dp(12),dp(12),dp(12));
        foldArea=new FoldWindow.SafeArea(this,root);root.setOnApplyWindowInsetsListener((v,insets)->foldArea.windowInsets(insets,dp(12),dp(12),dp(12),dp(12)));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);Button back=new Button(this);back.setText(text("cancel"));back.setOnClickListener(v->finish());toolbar.addView(back);TextView title=new TextView(this);title.setText(text("galleryTitle"));title.setTextSize(20);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));confirm=new Button(this);confirm.setOnClickListener(v->{setResult(RESULT_OK,AttachmentSelection.result(List.copyOf(selected)));finish();});toolbar.addView(confirm);root.addView(toolbar);
        notice=new TextView(this);notice.setTextColor(Color.DKGRAY);root.addView(notice);access=new Button(this);access.setText(text("galleryAccess"));access.setOnClickListener(v->permission());root.addView(access);
        grid=new GridView(this);grid.setColumnWidth(dp(120));grid.setNumColumns(GridView.AUTO_FIT);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setVerticalSpacing(dp(6));grid.setHorizontalSpacing(dp(6));adapter=new Adapter();grid.setAdapter(adapter);grid.setOnItemClickListener((p,v,position,id)->toggle(photos.get(position).uri));grid.setRecyclerListener(view->{Holder h=(Holder)view.getTag();if(h!=null)h.unbind();});root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        more=new Button(this);more.setText(text("galleryMore"));more.setOnClickListener(v->load());root.addView(more);setContentView(root);update();if(allowed())load();else permission();
    }
    private String text(String key,Object... args){return AndroidText.get(this,key,args);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private boolean allowed(){return Build.VERSION.SDK_INT>=33?checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED||Build.VERSION.SDK_INT>=34&&checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
    private void permission(){requestPermissions(Build.VERSION.SDK_INT>=34?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}:new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE},1);}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==1){photos.clear();exhausted=false;adapter.notifyDataSetChanged();if(allowed())load();else update();}}
    private void toggle(Uri uri){if(!selected.remove(uri)){if(selected.size()>=AttachmentBatchQueue.MAX_PENDING){Toast.makeText(this,text("attachmentBatchLimit",AttachmentBatchQueue.MAX_PENDING),Toast.LENGTH_SHORT).show();return;}selected.add(uri);}adapter.notifyDataSetChanged();update();}
    private void update(){confirm.setText(text("attachmentSelectionCount",selected.size()));confirm.setEnabled(!selected.isEmpty());access.setVisibility(allowed()?View.GONE:View.VISIBLE);notice.setText(!allowed()?text("galleryPermission"):photos.isEmpty()&&!loading?text("galleryEmpty"):"");more.setEnabled(allowed()&&!loading&&!exhausted);}
    private void load(){if(loading||exhausted||!allowed())return;loading=true;update();long before=photos.isEmpty()?Long.MAX_VALUE:photos.get(photos.size()-1).id;
        try{query.execute(()->{List<Photo> page=new ArrayList<>();boolean failed=false;
            try{Bundle args=new Bundle();args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,MediaStore.Images.Media._ID+" < ?");args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{Long.toString(before)});args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,new String[]{MediaStore.Images.Media._ID});args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);args.putInt(ContentResolver.QUERY_ARG_LIMIT,100);
                try(Cursor cursor=getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME},args,null)){if(cursor!=null)while(cursor.moveToNext()&&page.size()<100){long id=cursor.getLong(0);page.add(new Photo(id,ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id),cursor.getString(1)));}}
            }catch(Exception denied){failed=true;}boolean error=failed;runOnUiThread(()->{if(isDestroyed())return;loading=false;if(error){notice.setText(text("galleryPermission"));more.setEnabled(false);access.setVisibility(View.VISIBLE);return;}photos.addAll(page);exhausted=page.size()<100;adapter.notifyDataSetChanged();update();});
        });}catch(RejectedExecutionException busy){loading=false;update();}
    }
    protected void onSaveInstanceState(Bundle state){state.putParcelableArrayList("selected",new ArrayList<>(selected));super.onSaveInstanceState(state);}
    protected void onDestroy(){thumbnails.close();query.shutdownNow();super.onDestroy();}
    private final class Holder {final ImageView image;final TextView label,order;Uri uri;AutoCloseable subscription;Holder(ImageView image,TextView label,TextView order){this.image=image;this.label=label;this.order=order;}void unbind(){if(subscription!=null)try{subscription.close();}catch(Exception ignored){}subscription=null;uri=null;}}
    private final class Adapter extends BaseAdapter {
        public int getCount(){return photos.size();}public Object getItem(int i){return photos.get(i);}public long getItemId(int i){return photos.get(i).id;}public boolean hasStableIds(){return true;}
        public View getView(int position,View recycled,android.view.ViewGroup parent){Holder h;
            if(recycled==null){FrameLayout frame=new FrameLayout(GalleryActivity.this);ImageView image=new ImageView(GalleryActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(image,new FrameLayout.LayoutParams(-1,dp(132)));TextView name=new TextView(GalleryActivity.this);name.setTextSize(11);name.setTextColor(Color.WHITE);name.setBackgroundColor(0x88000000);name.setSingleLine(true);FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM);frame.addView(name,bottom);TextView order=new TextView(GalleryActivity.this);order.setTextColor(Color.WHITE);order.setBackgroundColor(0xff256b51);order.setGravity(Gravity.CENTER);frame.addView(order,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.TOP|Gravity.END));h=new Holder(image,name,order);frame.setTag(h);recycled=frame;}
            else h=(Holder)recycled.getTag();h.unbind();Photo photo=photos.get(position);h.uri=photo.uri;h.label.setText(photo.name);h.image.setImageResource(R.drawable.outline_photo_24);int index=new ArrayList<>(selected).indexOf(photo.uri);h.order.setVisibility(index>=0?View.VISIBLE:View.GONE);h.order.setText(Integer.toString(index+1));recycled.setContentDescription(photo.name+" "+(index>=0?Integer.toString(index+1):""));Uri expected=h.uri;h.subscription=thumbnails.bind(expected,bitmap->{if(expected.equals(h.uri)&&bitmap!=null)h.image.setImageBitmap(bitmap);});return recycled;
        }
    }
}
