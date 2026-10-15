package dev.ghost.nearbyim;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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
    private FoldWindow.SafeArea foldArea;
    private SurfaceView preview;
    private FrameLayout holder;
    private TextView status;
    private TextView focusStatus;
    private Button refocus;
    private View focusIndicator;
    private Camera camera;
    private QrCameraFocus cameraFocus;
    private final Handler focusHandler=new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int previewWidth,previewHeight,displayRotation;
    private boolean frontFacing;
    private int sensorOrientation;
    private android.hardware.display.DisplayManager displays;
    private final android.hardware.display.DisplayManager.DisplayListener displayListener=new android.hardware.display.DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){updatePreviewGeometry();}};
    private float pendingTapX=Float.NaN,pendingTapY=Float.NaN,focusTapX,focusTapY;
    private long lastFrame;
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    protected void onStart(){super.onStart();if(foldArea!=null)foldArea.start();displays=getSystemService(android.hardware.display.DisplayManager.class);if(displays!=null)displays.registerDisplayListener(displayListener,focusHandler);}
    protected void onStop(){if(displays!=null)displays.unregisterDisplayListener(displayListener);if(foldArea!=null)foldArea.stop();super.onStop();}
    protected void attachBaseContext(Context context){super.attachBaseContext(AppLanguage.wrap(context));}
    public void onCreate(Bundle saved){
        super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        foldArea=new FoldWindow.SafeArea(this,root);root.setOnApplyWindowInsetsListener((v,insets)->foldArea.windowInsets(insets,0,0,0,0));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);ImageButton back=new ImageButton(this);back.setImageResource(R.drawable.outline_arrow_back_24);back.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));back.setBackgroundColor(Color.TRANSPARENT);back.setContentDescription(text("close"));back.setOnClickListener(v->finish());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title=new TextView(this);title.setText(text("verificationScan"));title.setTextColor(Color.WHITE);title.setTextSize(20);toolbar.addView(title);root.addView(toolbar);
        holder=new FrameLayout(this);holder.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)updatePreviewGeometry();});preview=new SurfaceView(this);preview.setTag("qrPreview");preview.getHolder().addCallback(this);preview.setContentDescription(text("verificationFocusHint"));preview.setFocusable(true);
        preview.setOnClickListener(v->{float x=Float.isNaN(pendingTapX)?preview.getWidth()/2f:pendingTapX,y=Float.isNaN(pendingTapY)?preview.getHeight()/2f:pendingTapY;pendingTapX=pendingTapY=Float.NaN;focusAt(x,y);});
        preview.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_UP){pendingTapX=event.getX();pendingTapY=event.getY();v.performClick();}else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){pendingTapX=pendingTapY=Float.NaN;}return true;});
        holder.addView(preview,new FrameLayout.LayoutParams(-1,-1));focusIndicator=new View(this);focusIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);focusIndicator.setVisibility(View.INVISIBLE);holder.addView(focusIndicator,new FrameLayout.LayoutParams(dp(72),dp(72)));root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout focusControls=new LinearLayout(this);focusControls.setGravity(Gravity.CENTER_VERTICAL);focusControls.setPadding(dp(20),dp(12),dp(20),0);LinearLayout focusCopy=new LinearLayout(this);focusCopy.setOrientation(LinearLayout.VERTICAL);TextView focusHint=new TextView(this);focusHint.setTag("qrFocusHint");focusHint.setText(text("verificationFocusHint"));focusHint.setTextColor(Color.WHITE);focusHint.setTextSize(14);focusCopy.addView(focusHint);focusStatus=new TextView(this);focusStatus.setTag("qrFocusStatus");focusStatus.setVisibility(View.INVISIBLE);focusStatus.setTextColor(Color.WHITE);focusStatus.setTextSize(12);focusCopy.addView(focusStatus);focusControls.addView(focusCopy,new LinearLayout.LayoutParams(0,-2,1));
        refocus=new Button(this);refocus.setTag("qrRefocus");refocus.setText(text("verificationRefocus"));refocus.setTextSize(14);refocus.setEnabled(false);refocus.setMinHeight(dp(48));refocus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48,60,71)));android.graphics.drawable.Drawable focusIcon=getDrawable(R.drawable.outline_refresh_24).mutate();focusIcon.setTint(Color.WHITE);refocus.setTextColor(Color.WHITE);refocus.setCompoundDrawablesRelativeWithIntrinsicBounds(focusIcon,null,null,null);refocus.setCompoundDrawablePadding(dp(6));refocus.setOnClickListener(v->focusAt(preview.getWidth()/2f,preview.getHeight()/2f));focusControls.addView(refocus);root.addView(focusControls);
        status=new TextView(this);status.setText(text("verificationExplain"));status.setTextSize(14);status.setTextColor(Color.WHITE);status.setPadding(dp(20),dp(16),dp(20),dp(24));root.addView(status);setContentView(root);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    }
    protected void onResume(){super.onResume();resumed=true;startCamera();}
    protected void onPause(){resumed=false;stopCamera();super.onPause();}
    protected void onDestroy(){stopCamera();worker.shutdownNow();super.onDestroy();}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==1){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status.setText(text("verificationCameraPermission"));}}
    public void surfaceCreated(SurfaceHolder surface){startCamera();}
    public void surfaceChanged(SurfaceHolder surface,int format,int width,int height){updatePreviewGeometry();}
    public void surfaceDestroyed(SurfaceHolder surface){stopCamera();}
    private void updatePreviewGeometry(){
        Camera selected=camera;if(selected==null||holder==null||previewWidth<=0||previewHeight<=0)return;
        int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;
        int rotation=frontFacing?(360-(sensorOrientation+degrees)%360)%360:(sensorOrientation-degrees+360)%360;
        var fit=QrPreviewGeometry.fit(holder.getWidth(),holder.getHeight(),previewWidth,previewHeight,rotation);
        if(fit.width()==0)return;
        try{if(rotation!=displayRotation){selected.setDisplayOrientation(rotation);displayRotation=rotation;}}
        catch(RuntimeException unavailable){return;}
        ViewGroup.LayoutParams previous=preview.getLayoutParams();
        if(previous.width!=fit.width()||previous.height!=fit.height()){
            preview.setLayoutParams(new FrameLayout.LayoutParams(fit.width(),fit.height(),Gravity.CENTER));focusIndicator.setVisibility(View.INVISIBLE);pendingTapX=pendingTapY=Float.NaN;
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void startCamera(){
        if(!resumed||camera!=null||!preview.getHolder().getSurface().isValid()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            int count=Camera.getNumberOfCameras();if(count==0)throw new IllegalStateException("No camera");int selected=0;Camera.CameraInfo info=new Camera.CameraInfo();for(int i=0;i<count;i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){selected=i;break;}}
            Camera.CameraInfo chosen=new Camera.CameraInfo();Camera.getCameraInfo(selected,chosen);Camera opened=Camera.open(selected);camera=opened;Camera.Parameters parameters=opened.getParameters();Camera.Size size=parameters.getPreviewSize();
            List<Camera.Size> sizes=parameters.getSupportedPreviewSizes();if(sizes!=null){size=null;for(Camera.Size candidate:sizes)if(candidate.width<=1024&&candidate.height<=768&&(size==null||candidate.width*candidate.height>size.width*size.height))size=candidate;}
            if(size==null||size.width>2048||size.height>2048)throw new IllegalStateException("No bounded preview");parameters.setPreviewSize(size.width,size.height);parameters.setPreviewFormat(ImageFormat.NV21);opened.setParameters(parameters);
            QrCameraFocus focus=new QrCameraFocus(new QrCameraFocus.Device(){
                public List<String> supportedModes(){return opened.getParameters().getSupportedFocusModes();}
                public String currentMode(){return opened.getParameters().getFocusMode();}
                public void setMode(String mode){Camera.Parameters focusParameters=opened.getParameters();focusParameters.setFocusMode(mode);opened.setParameters(focusParameters);}
                public void setFocusArea(QrFocusPoint.Area area){applyCameraArea(opened,area,true);applyCameraArea(opened,area,false);}
                public void autoFocus(QrCameraFocus.Completion completed){opened.autoFocus((success,source)->completed.completed(success));}
                public void cancelAutoFocus(){opened.cancelAutoFocus();}
            },new QrCameraFocus.Scheduler(){
                public void postDelayed(Runnable task,long delayMillis){focusHandler.postDelayed(task,delayMillis);}
                public void remove(Runnable task){focusHandler.removeCallbacks(task);}
            },(state,area)->{if(camera==opened&&resumed)showFocusState(state,area);});cameraFocus=focus;focus.configure();
            int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;int rotation=chosen.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(chosen.orientation+degrees)%360)%360:(chosen.orientation-degrees+360)%360;opened.setDisplayOrientation(rotation);
            sensorOrientation=chosen.orientation;previewWidth=size.width;previewHeight=size.height;displayRotation=rotation;frontFacing=chosen.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            int width=size.width,height=size.height;holder.post(this::updatePreviewGeometry);
            opened.setPreviewDisplay(preview.getHolder());opened.setPreviewCallbackWithBuffer((data,source)->{
                long now=SystemClock.elapsedRealtime();if(data!=null&&data.length>=width*height&&now-lastFrame>=250&&decoding.compareAndSet(false,true)){
                    lastFrame=now;byte[] luma=Arrays.copyOf(data,width*height);
                    try{worker.execute(()->{try{String decoded=QrCodes.decodeLuma(luma,width,height);if(decoded!=null)runOnUiThread(()->{if(camera==opened&&resumed&&!isFinishing()){setResult(RESULT_OK,new Intent().putExtra("code",decoded));finish();}});}catch(RuntimeException invalidFrame){}finally{decoding.set(false);}});}catch(RejectedExecutionException stopped){decoding.set(false);}
                }
                if(camera==source&&data!=null)try{source.addCallbackBuffer(data);}catch(RuntimeException stopped){}
            });
            int bytes=width*height*3/2;opened.addCallbackBuffer(new byte[bytes]);opened.addCallbackBuffer(new byte[bytes]);opened.startPreview();focus.start();
        }catch(Exception unavailable){stopCamera();status.setText(text("verificationCameraUnavailable"));}
    }
    private void focusAt(float x,float y){QrCameraFocus focus=cameraFocus;if(focus==null)return;QrFocusPoint.Area area=QrFocusPoint.at(x,y,preview.getWidth(),preview.getHeight(),previewWidth,previewHeight,displayRotation,frontFacing);if(area==null)return;focusTapX=x;focusTapY=y;focus.focusNow(area);}
    private void applyCameraArea(Camera opened,QrFocusPoint.Area area,boolean focus){
        // Some vendors support focus areas but reject metering areas (or vice versa).
        try{Camera.Parameters parameters=opened.getParameters();if((focus?parameters.getMaxNumFocusAreas():parameters.getMaxNumMeteringAreas())<=0)return;List<Camera.Area> areas=area==null?null:Collections.singletonList(new Camera.Area(new Rect(area.left,area.top,area.right,area.bottom),1000));if(focus)parameters.setFocusAreas(areas);else parameters.setMeteringAreas(areas);opened.setParameters(parameters);}catch(RuntimeException unsupported){}
    }
    private void showFocusState(QrCameraFocus.State state,QrFocusPoint.Area area){
        refocus.setEnabled(state!=QrCameraFocus.State.UNAVAILABLE);
        String key=state==QrCameraFocus.State.FOCUSING?"verificationFocusing":state==QrCameraFocus.State.FOCUSED?"verificationFocusLocked":state==QrCameraFocus.State.FAILED?"verificationFocusFailed":state==QrCameraFocus.State.UNAVAILABLE?"verificationFocusUnavailable":"verificationFocusHint";
        focusStatus.setAccessibilityLiveRegion(area==null?View.ACCESSIBILITY_LIVE_REGION_NONE:View.ACCESSIBILITY_LIVE_REGION_POLITE);focusStatus.setText(state==QrCameraFocus.State.IDLE?"":text(key));focusStatus.setVisibility(state==QrCameraFocus.State.IDLE?View.INVISIBLE:View.VISIBLE);
        if(state==QrCameraFocus.State.IDLE||state==QrCameraFocus.State.UNAVAILABLE){focusIndicator.setVisibility(View.INVISIBLE);return;}
        int color=state==QrCameraFocus.State.FOCUSED?Color.rgb(116,224,140):state==QrCameraFocus.State.FAILED?Color.rgb(255,151,127):Color.WHITE;GradientDrawable border=new GradientDrawable();border.setColor(Color.TRANSPARENT);border.setCornerRadius(dp(6));border.setStroke(dp(2),color);focusIndicator.setBackground(border);
        int side=Math.min(dp(72),Math.min(preview.getWidth(),preview.getHeight()));if(side<=0)return;float x=area==null?preview.getWidth()/2f:focusTapX,y=area==null?preview.getHeight()/2f:focusTapY;FrameLayout.LayoutParams placement=new FrameLayout.LayoutParams(side,side);placement.leftMargin=preview.getLeft()+Math.max(0,Math.min(preview.getWidth()-side,Math.round(x-side/2f)));placement.topMargin=preview.getTop()+Math.max(0,Math.min(preview.getHeight()-side,Math.round(y-side/2f)));focusIndicator.setLayoutParams(placement);focusIndicator.setVisibility(View.VISIBLE);
    }
    private void stopCamera(){QrCameraFocus previousFocus=cameraFocus;cameraFocus=null;if(previousFocus!=null)previousFocus.stop();if(refocus!=null)refocus.setEnabled(false);if(focusIndicator!=null)focusIndicator.setVisibility(View.INVISIBLE);pendingTapX=pendingTapY=Float.NaN;Camera previous=camera;camera=null;if(previous!=null){try{previous.setPreviewCallbackWithBuffer(null);previous.stopPreview();}catch(RuntimeException ignored){}try{previous.release();}catch(RuntimeException ignored){}}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String text(String key){return AndroidText.get(this,key);}
}
