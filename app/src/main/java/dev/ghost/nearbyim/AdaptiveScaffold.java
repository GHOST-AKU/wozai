package dev.ghost.nearbyim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
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
    private int page;private boolean detail,railConfigured;
    private AdaptiveLayout.Fold fold;
    private AdaptiveLayout.Plan plan;
    Runnable geometryChanging,geometryChanged;private Rect previousDialogBounds;
    java.util.function.Consumer<Boolean> navigationChanged;
    AdaptiveScaffold(Context context){super(context);setWillNotDraw(false);}
    void bind(View heading,View error,View home,View nearby,View settings,View chat,View empty,LinearLayout navigation,int lineColor){
        this.heading=heading;this.error=error;this.home=home;this.nearby=nearby;this.settings=settings;this.chat=chat;this.empty=empty;this.navigation=navigation;divider.setColor(lineColor);requestLayout();
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
        var config=getResources().getConfiguration();var next=AdaptiveLayout.compute(w,h,getResources().getDisplayMetrics().density,config.fontScale,getLayoutDirection()==LAYOUT_DIRECTION_RTL,page==0,local);if(plan!=null&&!plan.equals(next)&&geometryChanging!=null)geometryChanging.run();plan=next;
        boolean dual=plan.dual();configureNavigation(plan.rail());
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
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){for(var entry:positions.entrySet()){Rect p=entry.getValue();entry.getKey().layout(p.left,p.top,p.right,p.bottom);}Rect bounds=dialogBounds();if(!bounds.equals(previousDialogBounds)){previousDialogBounds=bounds;if(geometryChanged!=null)geometryChanged.run();}}
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(plan==null)return;
        if(plan.dual()){var p=plan.list();float x=(getLayoutDirection()==LAYOUT_DIRECTION_RTL?p.left():p.right())+getPaddingLeft();canvas.drawRect(x,getPaddingTop()+p.top(),x+Math.max(1,dp(1)),getPaddingTop()+p.bottom(),divider);}
    }
    protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT);}
    Rect dialogBounds(){
        if(plan==null)return new Rect(getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());
        var p=detail&&plan.dual()?plan.detail():plan.list();return new Rect(p.left()+getPaddingLeft(),p.top()+getPaddingTop(),p.right()+getPaddingLeft(),p.bottom()+getPaddingTop());
    }
}
