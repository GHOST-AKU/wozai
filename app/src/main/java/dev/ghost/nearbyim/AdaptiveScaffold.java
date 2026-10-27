package dev.ghost.nearbyim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.*;
import android.widget.LinearLayout;
import dev.ghost.nearbyim.core.AdaptiveLayout;
import java.util.*;

/** Repositions the same live Views; resizing never reconstructs a chat or its controller. */
final class AdaptiveScaffold extends ViewGroup {
    private View heading,error,home,nearby,settings,chat,empty;
    private LinearLayout navigation;
    private final Map<View,Rect> positions=new IdentityHashMap<>();
    private final Paint divider=new Paint();
    private final PaneDivider dividerControl;
    private float splitPosition,splitMin,splitMax;
    private boolean movableDivider;
    private int handleColor,activeHandleColor;

    private int page;private boolean detail,railConfigured;
    private AdaptiveLayout.Fold fold;
    private AdaptiveLayout.Plan plan;
    Runnable geometryChanging,geometryChanged;private Rect previousDialogBounds;
    java.util.function.Consumer<Boolean> navigationChanged;
    AdaptiveScaffold(Context context){
        super(context);setWillNotDraw(false);
        splitPosition=context.getSharedPreferences("ui",Context.MODE_PRIVATE).getFloat("paneSplit",Float.NaN);
        dividerControl=new PaneDivider(context);
    }
    void bind(View heading,View error,View home,View nearby,View settings,View chat,View empty,LinearLayout navigation,int lineColor,int handleColor,int activeHandleColor){
        this.heading=heading;this.error=error;this.home=home;this.nearby=nearby;this.settings=settings;this.chat=chat;this.empty=empty;this.navigation=navigation;divider.setColor(lineColor);this.handleColor=handleColor;this.activeHandleColor=activeHandleColor;addView(dividerControl);requestLayout();
    }
    void show(int page,boolean detail){if(this.page!=page||this.detail!=detail){this.page=page;this.detail=detail;requestLayout();}}
    void fold(AdaptiveLayout.Fold value){if(!Objects.equals(fold,value)){fold=value;requestLayout();}}
    boolean dualPane(){return plan!=null&&plan.dual();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void visible(View view,boolean value){view.setVisibility(value?VISIBLE:GONE);}
    private void configureNavigation(boolean rail){
        if(navigation.getOrientation()==(rail?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL)&&railConfigured)return;
        railConfigured=true;navigation.setOrientation(rail?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        navigation.setGravity(rail?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);
        navigation.setPadding(dp(rail?4:12),dp(rail?16:9),dp(rail?4:12),dp(rail?16:7));
        for(int i=0;i<navigation.getChildCount();i++){
            View child=navigation.getChildAt(i);Object tag=child.getTag();
            if(tag instanceof Float weight){visible(child,rail);child.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(16),weight));}
            else if("railCompose".equals(tag)){visible(child,rail);child.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));}
            else child.setLayoutParams(rail?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
        }
        if(navigationChanged!=null)navigationChanged.accept(rail);
    }
    private int wrap(View view,int width,int maxHeight){
        if(view.getVisibility()==GONE)return 0;
        view.measure(MeasureSpec.makeMeasureSpec(Math.max(0,width),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,maxHeight),MeasureSpec.AT_MOST));return view.getMeasuredHeight();
    }
    private void box(View view,int left,int top,int right,int bottom){
        if(view.getVisibility()==GONE)return;
        Rect area=new Rect(left+getPaddingLeft(),top+getPaddingTop(),Math.max(left,right)+getPaddingLeft(),Math.max(top,bottom)+getPaddingTop());
        positions.put(view,area);view.measure(MeasureSpec.makeMeasureSpec(area.width(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(area.height(),MeasureSpec.EXACTLY));
    }
    protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);setMeasuredDimension(width,height);positions.clear();
        if(navigation==null)return;
        int w=Math.max(0,width-getPaddingLeft()-getPaddingRight()),h=Math.max(0,height-getPaddingTop()-getPaddingBottom());
        AdaptiveLayout.Fold local=fold==null?null:new AdaptiveLayout.Fold(fold.left()-getPaddingLeft(),fold.top()-getPaddingTop(),fold.right()-getPaddingLeft(),fold.bottom()-getPaddingTop(),fold.vertical(),fold.separating(),fold.occluding());
        var config=getResources().getConfiguration();var next=AdaptiveLayout.compute(w,h,getResources().getDisplayMetrics().density,config.fontScale,getLayoutDirection()==LAYOUT_DIRECTION_RTL,page==0,local,splitPosition);if(plan!=null&&!plan.equals(next)&&geometryChanging!=null)geometryChanging.run();plan=next;
        boolean dual=plan.dual();configureNavigation(plan.rail());
        movableDivider=dual&&(plan.list().right()==plan.detail().left()||plan.detail().right()==plan.list().left());
        if(local!=null&&(local.separating()||local.occluding())&&local.vertical())movableDivider=false;
        visible(dividerControl,movableDivider);
        if(movableDivider){
            var low=AdaptiveLayout.compute(w,h,getResources().getDisplayMetrics().density,config.fontScale,getLayoutDirection()==LAYOUT_DIRECTION_RTL,true,local,0);
            var high=AdaptiveLayout.compute(w,h,getResources().getDisplayMetrics().density,config.fontScale,getLayoutDirection()==LAYOUT_DIRECTION_RTL,true,local,1);
            splitMin=getLayoutDirection()==LAYOUT_DIRECTION_RTL?low.list().left():low.list().right();
            splitMax=getLayoutDirection()==LAYOUT_DIRECTION_RTL?high.list().left():high.list().right();
        }

        visible(heading,!detail||dual);visible(navigation,plan.rail()||!detail);
        visible(home,page==0&&(dual||!detail));visible(chat,page==0&&detail);visible(empty,page==0&&dual&&!detail);
        visible(nearby,page==1);visible(settings,page==2);
        var area=plan.area();var primary=plan.list();
        int bottom=primary.bottom();
        if(navigation.getVisibility()==VISIBLE){
            if(plan.rail()){var n=plan.navigation();box(navigation,n.left(),n.top(),n.right(),n.bottom());}
            else{int navHeight=wrap(navigation,area.width(),Math.min(area.height(),dp(160)));bottom=Math.max(primary.top(),bottom-navHeight);box(navigation,area.left(),bottom,area.right(),primary.bottom());}
        }
        int left=primary.left(),right=primary.right();
        if(page!=0){int gap=Math.max(0,(right-left-dp(720))/2);left+=gap;right-=gap;}
        int y=primary.top();int titleHeight=wrap(heading,right-left,bottom-y);box(heading,left,y,right,y+titleHeight);y+=titleHeight;
        int errorHeight=wrap(error,right-left,bottom-y);box(error,left,y,right,y+errorHeight);y+=errorHeight;
        box(home,left,y,right,bottom);box(nearby,left,y,right,bottom);box(settings,left,y,right,bottom);
        if(dual){var d=plan.detail();box(chat,d.left(),d.top(),d.right(),d.bottom());box(empty,d.left(),d.top(),d.right(),d.bottom());}
        else box(chat,left,y,right,bottom);
        if(movableDivider){
            int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?plan.list().left():plan.list().right();
            box(dividerControl,x-dp(24),primary.top(),x+dp(24),primary.bottom());
        }
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){for(var entry:positions.entrySet()){Rect p=entry.getValue();entry.getKey().layout(p.left,p.top,p.right,p.bottom);}Rect bounds=dialogBounds();if(!bounds.equals(previousDialogBounds)){previousDialogBounds=bounds;if(geometryChanged!=null)geometryChanged.run();}}
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(plan==null)return;
        if(plan.dual()){var p=plan.list();float x=(getLayoutDirection()==LAYOUT_DIRECTION_RTL?p.left():p.right())+getPaddingLeft();if(!movableDivider)canvas.drawRect(x,getPaddingTop()+p.top(),x+Math.max(1,dp(1)),getPaddingTop()+p.bottom(),divider);}
    }
    private void rememberSplit(){
        getContext().getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putFloat("paneSplit",splitPosition).apply();
    }
    private float constrainDivider(float x,boolean snap,boolean latched){
        x=Math.max(splitMin,Math.min(splitMax,x));
        float center=(getWidth()-getPaddingLeft()-getPaddingRight())/2f;
        return snap&&center>=splitMin&&center<=splitMax?AdaptiveLayout.snapDivider(x,center,getResources().getDisplayMetrics().density,latched):x;
    }
    private void moveDivider(float x,boolean snap,boolean latched){
        int width=getWidth()-getPaddingLeft()-getPaddingRight();if(width<=0||!movableDivider)return;
        splitPosition=constrainDivider(x,snap,latched)/width;
        splitPosition=Math.max(0,Math.min(1,splitPosition));requestLayout();
    }
    private final class PaneDivider extends View {
        private float downX,startX,original;private boolean moved,snapped;
        private final Paint handle=new Paint(Paint.ANTI_ALIAS_FLAG);
        PaneDivider(Context context){
            super(context);setTag("paneDivider");setFocusable(true);setClickable(true);
            setContentDescription(AndroidText.get(context,"paneDivider"));
            setOnClickListener(v->{moveDivider((getWidthOfWindow())/2f,false,false);rememberSplit();});
        }
        private int getWidthOfWindow(){return AdaptiveScaffold.this.getWidth()-getPaddingOfWindow();}
        private int getPaddingOfWindow(){return AdaptiveScaffold.this.getPaddingLeft()+AdaptiveScaffold.this.getPaddingRight();}
        protected void onDraw(Canvas canvas){
            float x=getWidth()/2f;canvas.drawRect(x,0,x+Math.max(1,dp(1)),getHeight(),divider);
            handle.setColor(isPressed()?activeHandleColor:handleColor);
            canvas.drawRoundRect(x-dp(2),getHeight()/2f-dp(16),x+dp(2),getHeight()/2f+dp(16),dp(2),dp(2),handle);
        }
        public boolean onTouchEvent(MotionEvent event){
            if(!movableDivider)return false;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getRawX();startX=getLeft()+getWidth()/2f-AdaptiveScaffold.this.getPaddingLeft();original=splitPosition;moved=false;snapped=false;
                    setPressed(true);getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:
                    float delta=event.getRawX()-downX;
                    if(!moved&&Math.abs(delta)<ViewConfiguration.get(getContext()).getScaledTouchSlop())return true;
                    moved=true;float raw=startX+delta;
                    float next=constrainDivider(raw,true,snapped);
                    boolean nowSnapped=next==getWidthOfWindow()/2f;
                    if(nowSnapped&&!snapped)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    snapped=nowSnapped;moveDivider(next,false,false);return true;
                case MotionEvent.ACTION_UP:
                    setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);
                    if(moved)rememberSplit();else performClick();return true;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);splitPosition=original;requestLayout();return true;
                default:return true;
            }
        }
        public boolean onKeyDown(int key,KeyEvent event){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                moveDivider(getLeft()+getWidth()/2f-AdaptiveScaffold.this.getPaddingLeft()+(key==KeyEvent.KEYCODE_DPAD_LEFT?-dp(24):dp(24)),true,false);rememberSplit();return true;
            }
            if(key==KeyEvent.KEYCODE_MOVE_HOME){performClick();return true;}
            return super.onKeyDown(key,event);
        }
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.SeekBar.class.getName());
            float position=(getLeft()+getWidth()/2f-AdaptiveScaffold.this.getPaddingLeft())/Math.max(1,getWidthOfWindow());
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,splitMin/Math.max(1,getWidthOfWindow())*100,splitMax/Math.max(1,getWidthOfWindow())*100,position*100));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,AndroidText.get(getContext(),"paneDividerCenter")));
        }
        public boolean performAccessibilityAction(int action,Bundle arguments){
            if(!movableDivider)return false;
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&arguments!=null){
                float percent=arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN);
                if(!Float.isFinite(percent))return false;moveDivider(getWidthOfWindow()*percent/100f,true,false);rememberSplit();return true;
            }
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
                moveDivider(getLeft()+getWidth()/2f-AdaptiveScaffold.this.getPaddingLeft()+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?dp(24):-dp(24)),true,false);rememberSplit();return true;
            }
            return super.performAccessibilityAction(action,arguments);
        }
    }
    protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT);}
    Rect dialogBounds(){
        if(plan==null)return new Rect(getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());
        var p=detail&&plan.dual()?plan.detail():plan.list();return new Rect(p.left()+getPaddingLeft(),p.top()+getPaddingTop(),p.right()+getPaddingLeft(),p.bottom()+getPaddingTop());
    }
}
