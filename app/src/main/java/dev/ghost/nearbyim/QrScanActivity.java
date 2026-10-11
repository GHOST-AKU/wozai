package dev.ghost.nearbyim;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.verification.QrCodes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera exists only while this internal scanner is visible; no frame is saved or sent. */
@SuppressWarnings("deprecation")
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView preview;
    private FrameLayout holder;
    private TextView status;
    private Camera camera;
    private boolean resumed;
    private long lastFrame;
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    protected void attachBaseContext(Context context){super.attachBaseContext(AppLanguage.wrap(context));}
    public void onCreate(Bundle saved){
        super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);ImageButton back=new ImageButton(this);back.setImageResource(R.drawable.outline_arrow_back_24);back.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));back.setBackgroundColor(Color.TRANSPARENT);back.setContentDescription(text("close"));back.setOnClickListener(v->finish());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title=new TextView(this);title.setText(text("verificationScan"));title.setTextColor(Color.WHITE);title.setTextSize(20);toolbar.addView(title);root.addView(toolbar);
        holder=new FrameLayout(this);preview=new SurfaceView(this);preview.getHolder().addCallback(this);holder.addView(preview,new FrameLayout.LayoutParams(-1,-1));root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setText(text("verificationExplain"));status.setTextSize(14);status.setTextColor(Color.WHITE);status.setPadding(dp(20),dp(16),dp(20),dp(24));root.addView(status);setContentView(root);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    }
    protected void onResume(){super.onResume();resumed=true;startCamera();}
    protected void onPause(){resumed=false;stopCamera();super.onPause();}
    protected void onDestroy(){stopCamera();worker.shutdownNow();super.onDestroy();}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==1){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status.setText(text("verificationCameraPermission"));}}
    public void surfaceCreated(SurfaceHolder surface){startCamera();}
    public void surfaceChanged(SurfaceHolder surface,int format,int width,int height){}
    public void surfaceDestroyed(SurfaceHolder surface){stopCamera();}
    @android.annotation.SuppressLint("MissingPermission")
    private void startCamera(){
        if(!resumed||camera!=null||!preview.getHolder().getSurface().isValid()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            int count=Camera.getNumberOfCameras();if(count==0)throw new IllegalStateException("No camera");int selected=0;Camera.CameraInfo info=new Camera.CameraInfo();for(int i=0;i<count;i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){selected=i;break;}}
            Camera.CameraInfo chosen=new Camera.CameraInfo();Camera.getCameraInfo(selected,chosen);Camera opened=Camera.open(selected);camera=opened;Camera.Parameters parameters=opened.getParameters();Camera.Size size=parameters.getPreviewSize();
            List<Camera.Size> sizes=parameters.getSupportedPreviewSizes();if(sizes!=null){size=null;for(Camera.Size candidate:sizes)if(candidate.width<=1024&&candidate.height<=768&&(size==null||candidate.width*candidate.height>size.width*size.height))size=candidate;}
            if(size==null||size.width>2048||size.height>2048)throw new IllegalStateException("No bounded preview");parameters.setPreviewSize(size.width,size.height);parameters.setPreviewFormat(ImageFormat.NV21);opened.setParameters(parameters);
            int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;int rotation=chosen.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(chosen.orientation+degrees)%360)%360:(chosen.orientation-degrees+360)%360;opened.setDisplayOrientation(rotation);
            int width=size.width,height=size.height;boolean swap=rotation%180!=0;float ratio=swap?(float)height/width:(float)width/height;holder.post(()->{if(camera==opened){int availableW=holder.getWidth(),availableH=holder.getHeight();int w=Math.min(availableW,Math.round(availableH*ratio));preview.setLayoutParams(new FrameLayout.LayoutParams(w,Math.round(w/ratio),Gravity.CENTER));}});
            opened.setPreviewDisplay(preview.getHolder());opened.setPreviewCallbackWithBuffer((data,source)->{
                long now=SystemClock.elapsedRealtime();if(data!=null&&data.length>=width*height&&now-lastFrame>=250&&decoding.compareAndSet(false,true)){
                    lastFrame=now;byte[] luma=Arrays.copyOf(data,width*height);
                    try{worker.execute(()->{try{String decoded=QrCodes.decodeLuma(luma,width,height);if(decoded!=null)runOnUiThread(()->{if(camera==opened&&resumed&&!isFinishing()){setResult(RESULT_OK,new Intent().putExtra("code",decoded));finish();}});}catch(RuntimeException invalidFrame){}finally{decoding.set(false);}});}catch(RejectedExecutionException stopped){decoding.set(false);}
                }
                if(camera==source&&data!=null)try{source.addCallbackBuffer(data);}catch(RuntimeException stopped){}
            });
            int bytes=width*height*3/2;opened.addCallbackBuffer(new byte[bytes]);opened.addCallbackBuffer(new byte[bytes]);opened.startPreview();
        }catch(Exception unavailable){stopCamera();status.setText(text("verificationCameraUnavailable"));}
    }
    private void stopCamera(){Camera previous=camera;camera=null;if(previous!=null){try{previous.setPreviewCallbackWithBuffer(null);previous.stopPreview();}catch(RuntimeException ignored){}try{previous.release();}catch(RuntimeException ignored){}}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String text(String key){return AndroidText.get(this,key);}
}
