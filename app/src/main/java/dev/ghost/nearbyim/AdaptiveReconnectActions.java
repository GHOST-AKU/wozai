package dev.ghost.nearbyim;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Keeps the complete connection explanation; stacks actions when text needs more room. */
final class AdaptiveReconnectActions extends ViewGroup {
    private boolean inline;
    private int hintHeight,actionHeight;
    AdaptiveReconnectActions(Context context){super(context);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),gap=dp(8),actions=0,count=0;
        View hint=getChildAt(0);
        for(int i=1;i<getChildCount();i++){
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            actions+=child.getMeasuredWidth();count++;
        }
        actions+=Math.max(0,count-1)*gap;
        inline=width-actions-gap>=dp(112*Math.max(1,getResources().getConfiguration().fontScale));
        hint.measure(MeasureSpec.makeMeasureSpec(inline?width-actions-gap:width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        hintHeight=hint.getMeasuredHeight();actionHeight=0;
        for(int i=1;i<getChildCount();i++){
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int childWidth=inline?child.getMeasuredWidth():Math.max(0,(width-Math.max(0,count-1)*gap)/Math.max(1,count));
            child.measure(MeasureSpec.makeMeasureSpec(childWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            actionHeight=Math.max(actionHeight,child.getMeasuredHeight());
        }
        setMeasuredDimension(width,inline?Math.max(hintHeight,actionHeight):hintHeight+gap+actionHeight);
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){
        boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;int width=r-l,height=b-t,gap=dp(8);
        View hint=getChildAt(0);int hintWidth=hint.getMeasuredWidth(),hintTop=inline?(height-hintHeight)/2:0;
        hint.layout(rtl?width-hintWidth:0,hintTop,rtl?width:hintWidth,hintTop+hintHeight);
        int x=inline?hintWidth+gap:0,top=inline?(height-actionHeight)/2:hintHeight+gap;
        for(int i=1;i<getChildCount();i++){
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int w=child.getMeasuredWidth(),h=child.getMeasuredHeight(),y=top+(actionHeight-h)/2;
            child.layout(rtl?width-x-w:x,y,rtl?width-x:x+w,y+h);x+=w+gap;
        }
    }
    protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT);}
}
