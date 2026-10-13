package dev.ghost.nearbyim;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.util.concurrent.*;

/** Internal, offline photo viewer; decoding and export never run on the main thread. */
public final class PhotoActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Uri photo;
    private ZoomImage image;
    private TextView notice;
    private View saveAction;
    private String pendingExportPeer,pendingExportRecord;
    protected void attachBaseContext(Context context){super.attachBaseContext(AppLanguage.wrap(context));}
    public void onCreate(Bundle state){
        super.onCreate(state);photo=getIntent().getData();if(state!=null){pendingExportPeer=state.getString("photoExportPeer");pendingExportRecord=state.getString("photoExportRecord");}
        if(photo==null||!"content".equals(photo.getScheme())||!(getPackageName()+".attachments").equals(photo.getAuthority())){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(action(R.drawable.outline_arrow_back_24,text("backToChats"),this::finish),new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title=new TextView(this);title.setText(photo.getQueryParameter("name"));title.setTextColor(Color.WHITE);title.setTextSize(15);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        saveAction=action(R.drawable.outline_file_download_24,text("gallerySave"),this::saveMenu);toolbar.addView(saveAction,new LinearLayout.LayoutParams(dp(48),dp(56)));root.addView(toolbar);
        FrameLayout canvas=new FrameLayout(this);image=new ZoomImage(this);image.setContentDescription(text("attachmentPhotoPreview",photo.getQueryParameter("name")));canvas.addView(image,new FrameLayout.LayoutParams(-1,-1));
        notice=new TextView(this);notice.setText(text("photoLoading"));notice.setTextColor(0xffbbbbbb);notice.setGravity(Gravity.CENTER);canvas.addView(notice,new FrameLayout.LayoutParams(-1,-1));root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(action(R.drawable.outline_zoom_out_24,text("photoZoomOut"),()->image.zoom(.5f)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView hint=new TextView(this);hint.setText(text("photoZoomHint"));hint.setTextColor(0xffaaaaaa);hint.setTextSize(12);hint.setGravity(Gravity.CENTER);footer.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
        footer.addView(action(R.drawable.outline_zoom_in_24,text("photoZoomIn"),()->image.zoom(2)),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(footer);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if(android.os.Build.VERSION.SDK_INT>=30){WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        else{getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);}
        setContentView(root);root.requestApplyInsets();
        worker.execute(()->{
            try{Bitmap bitmap=PhotoDecoder.decode(getContentResolver(),photo,2048);runOnUiThread(()->{if(isDestroyed()){bitmap.recycle();return;}image.setImageBitmap(bitmap);notice.setVisibility(View.GONE);});}
            catch(IOException|RuntimeException|OutOfMemoryError e){runOnUiThread(()->{if(!isDestroyed())notice.setText(text("photoUnavailable"));});}
        });
    }
    private void saveMenu(){PopupMenu menu=new PopupMenu(this,saveAction);menu.getMenu().add(0,1,0,text("gallerySave"));menu.getMenu().add(0,2,1,text("attachmentSaveAs"));menu.setOnMenuItemClickListener(item->{if(item.getItemId()==1)gallerySave();else save();return true;});menu.show();}
    private void gallerySave(){
        if(android.os.Build.VERSION.SDK_INT<=28&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},2);return;}
        try{
            String peer=getIntent().getStringExtra("peer"),encoded=getIntent().getStringExtra("record");if(peer==null||!peer.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||encoded==null)throw new IOException("Missing completed photo");AttachmentRecord record=AttachmentRecord.decode(encoded);
            java.nio.file.Path original=AttachmentTransfer.file(getFilesDir().toPath().resolve("attachments").resolve(peer),record.info,record.outgoing);
            if(!photo.getPathSegments().equals(java.util.List.of(peer,original.getFileName().toString())))throw new IOException("Photo identity mismatch");
            GalleryPublisher.get(this).export(peer,record,original,true).whenComplete((value,error)->runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,text(error==null?"gallerySaved":"gallerySaveFailed"),Toast.LENGTH_SHORT).show();}));
        }catch(IOException|RuntimeException failure){Toast.makeText(this,text("gallerySaveFailed"),Toast.LENGTH_SHORT).show();}
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] result){super.onRequestPermissionsResult(request,permissions,result);if(request==2){if(checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED)gallerySave();else Toast.makeText(this,text("gallerySavePermission"),Toast.LENGTH_SHORT).show();}}
    private void save(){
        try{pendingExportPeer=getIntent().getStringExtra("peer");pendingExportRecord=getIntent().getStringExtra("record");if(pendingExportPeer==null||pendingExportRecord==null)throw new IOException("Missing completed photo");AttachmentExportStore.requireComplete(AttachmentRecord.decode(pendingExportRecord));}
        catch(IOException invalid){Toast.makeText(this,text("attachmentFailed"),Toast.LENGTH_SHORT).show();return;}
        String name=photo.getQueryParameter("name");if(name==null)name=photo.getLastPathSegment();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(getContentResolver().getType(photo));intent.putExtra(Intent.EXTRA_TITLE,AttachmentInfo.safeName(name==null?"attachment":name));
        try{startActivityForResult(intent,1);}catch(android.content.ActivityNotFoundException e){pendingExportPeer=null;pendingExportRecord=null;Toast.makeText(this,text("attachmentFailed"),Toast.LENGTH_SHORT).show();}
    }
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=1)return;String peer=pendingExportPeer,encoded=pendingExportRecord;pendingExportPeer=null;pendingExportRecord=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||peer==null||encoded==null)return;Uri destination=data.getData();int flags=data.getFlags();
        try{AttachmentLocations.get(this).export(peer,AttachmentRecord.decode(encoded),destination,flags).whenComplete((value,error)->{if(error!=null)runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,text("attachmentFailed"),Toast.LENGTH_SHORT).show();});});}
        catch(IOException|RuntimeException invalid){Toast.makeText(this,text("attachmentFailed"),Toast.LENGTH_SHORT).show();}
    }
    protected void onSaveInstanceState(Bundle state){state.putString("photoExportPeer",pendingExportPeer);state.putString("photoExportRecord",pendingExportRecord);super.onSaveInstanceState(state);}
    protected void onDestroy(){worker.shutdownNow();if(image!=null)image.setImageDrawable(null);super.onDestroy();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String text(String key,Object...args){return AndroidText.get(this,key,args);}
    private ImageButton action(int resource,String description,Runnable action){
        ImageButton button=new ImageButton(this);button.setImageResource(resource);button.setImageTintList(ColorStateList.valueOf(Color.WHITE));button.setContentDescription(description);button.setBackgroundColor(Color.TRANSPARENT);button.setPadding(dp(12),dp(12),dp(12),dp(12));button.setOnClickListener(v->action.run());return button;
    }
    private static final class ZoomImage extends ImageView {
        private final Matrix transform=new Matrix();
        private final RectF bounds=new RectF();
        private final ScaleGestureDetector pinch;
        private final GestureDetector taps;
        private float factor=1,lastX,lastY;
        ZoomImage(Context context){
            super(context);setScaleType(ScaleType.MATRIX);setClickable(true);
            pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector detector){scale(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;}});
            taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
                public boolean onDown(android.view.MotionEvent event){return true;}
                public boolean onSingleTapConfirmed(android.view.MotionEvent event){return performClick();}
                public boolean onDoubleTap(android.view.MotionEvent event){if(factor>1.1f)fit();else scale(2.5f,event.getX(),event.getY());return true;}
            });
        }
        public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);fit();}
        protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);fit();}
        private void fit(){if(getDrawable()==null||getWidth()==0||getHeight()==0)return;factor=1;transform.setRectToRect(new RectF(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight()),new RectF(0,0,getWidth(),getHeight()),Matrix.ScaleToFit.CENTER);setImageMatrix(transform);}
        void zoom(float multiplier){scale(multiplier,getWidth()/2f,getHeight()/2f);}
        private void scale(float multiplier,float x,float y){if(getDrawable()==null)return;float target=Math.max(1,Math.min(6,factor*multiplier));transform.postScale(target/factor,target/factor,x,y);factor=target;constrain();}
        private void constrain(){
            if(getDrawable()==null)return;bounds.set(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight());transform.mapRect(bounds);
            float x=bounds.width()<=getWidth()?(getWidth()-bounds.width())/2-bounds.left:bounds.left>0?-bounds.left:bounds.right<getWidth()?getWidth()-bounds.right:0;
            float y=bounds.height()<=getHeight()?(getHeight()-bounds.height())/2-bounds.top:bounds.top>0?-bounds.top:bounds.bottom<getHeight()?getHeight()-bounds.bottom:0;
            transform.postTranslate(x,y);setImageMatrix(transform);
        }
        public boolean onTouchEvent(android.view.MotionEvent event){
            pinch.onTouchEvent(event);taps.onTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()&&event.getPointerCount()==1&&factor>1){transform.postTranslate(event.getX()-lastX,event.getY()-lastY);constrain();}
            lastX=event.getX();lastY=event.getY();return true;
        }
        public boolean performClick(){super.performClick();return true;}
    }
}
