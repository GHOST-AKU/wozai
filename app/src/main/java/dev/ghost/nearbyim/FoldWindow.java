package dev.ghost.nearbyim;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.*;
import dev.ghost.nearbyim.core.AdaptiveLayout;

/** Stable WindowManager Java bridge; owns only listeners, never the activity or a network process. */
final class FoldWindow {
    interface Listener {void changed(AdaptiveLayout.Fold feature);}
    private final Activity activity;private final Listener listener;
    private WindowInfoTrackerCallbackAdapter tracker;private Consumer<WindowLayoutInfo> callback;private long generation;
    FoldWindow(Activity activity,Listener listener){this.activity=activity;this.listener=listener;}
    void start(){
        if(callback!=null)return;long ticket=++generation;
        try{
            tracker=new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
            callback=info->{if(ticket!=generation)return;AdaptiveLayout.Fold found=null;
                for(DisplayFeature feature:info.getDisplayFeatures())if(feature instanceof FoldingFeature f&&(f.isSeparating()||f.getOcclusionType()==FoldingFeature.OcclusionType.FULL)){
                    Rect b=f.getBounds();int[] origin=new int[2];activity.getWindow().getDecorView().getLocationInWindow(origin);
                    found=new AdaptiveLayout.Fold(b.left-origin[0],b.top-origin[1],b.right-origin[0],b.bottom-origin[1],f.getOrientation()==FoldingFeature.Orientation.VERTICAL,f.isSeparating(),f.getOcclusionType()==FoldingFeature.OcclusionType.FULL);break;
                }
                listener.changed(found);
            };
            tracker.addWindowLayoutInfoListener(activity,activity::runOnUiThread,callback);
        }catch(RuntimeException unsupported){stop();listener.changed(null);}
    }
    void stop(){generation++;if(tracker!=null&&callback!=null)try{tracker.removeWindowLayoutInfoListener(callback);}catch(RuntimeException unavailable){}callback=null;tracker=null;}
    /** Single-page tools stay on a usable side of a separating/occluding fold. */
    static final class SafeArea {
        private final View root;private final FoldWindow watcher;private AdaptiveLayout.Fold fold;
        private int left,top,right,bottom;
        SafeArea(Activity activity,View root){this.root=root;watcher=new FoldWindow(activity,value->{fold=value;apply();});root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)apply();});}
        android.view.WindowInsets windowInsets(android.view.WindowInsets insets,int l,int t,int r,int b){
            if(android.os.Build.VERSION.SDK_INT>=30){var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());insets(l+bars.left,t+bars.top,r+bars.right,b+bars.bottom);}
            else insets(l+insets.getSystemWindowInsetLeft(),t+insets.getSystemWindowInsetTop(),r+insets.getSystemWindowInsetRight(),b+insets.getSystemWindowInsetBottom());return insets;
        }
        void insets(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;apply();}
        void start(){watcher.start();}void stop(){watcher.stop();}
        private void apply(){
            int w=Math.max(0,root.getWidth()-left-right),h=Math.max(0,root.getHeight()-top-bottom);
            if(fold==null||w==0||h==0){root.setPadding(left,top,right,bottom);return;}
            int[] origin=new int[2];root.getLocationInWindow(origin);
            var local=new AdaptiveLayout.Fold(fold.left()-origin[0]-left,fold.top()-origin[1]-top,fold.right()-origin[0]-left,fold.bottom()-origin[1]-top,fold.vertical(),fold.separating(),fold.occluding());
            var area=AdaptiveLayout.compute(w,h,root.getResources().getDisplayMetrics().density,root.getResources().getConfiguration().fontScale,false,false,local).area();
            root.setPadding(left+area.left(),top+area.top(),right+w-area.right(),bottom+h-area.bottom());
        }
    }
}
