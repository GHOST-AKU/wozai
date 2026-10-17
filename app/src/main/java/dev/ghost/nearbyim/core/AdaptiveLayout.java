package dev.ghost.nearbyim.core;

/** Window-local pixel geometry. Device model, physical display size and discovery mode are irrelevant. */
public final class AdaptiveLayout {
    private AdaptiveLayout(){}
    public record Box(int left,int top,int right,int bottom){
        public int width(){return Math.max(0,right-left);}public int height(){return Math.max(0,bottom-top);}
    }
    public record Fold(int left,int top,int right,int bottom,boolean vertical,boolean separating,boolean occluding){}
    public record Plan(Box area,Box navigation,Box list,Box detail,boolean rail,boolean dual){}
    public static Plan compute(int width,int height,float density,float fontScale,boolean rtl,boolean listDetail,Fold fold){
        int w=Math.max(0,width),h=Math.max(0,height);float d=Float.isFinite(density)&&density>0?density:1;
        float text=Float.isFinite(fontScale)?Math.max(1,fontScale):1;
        int railWidth=Math.round(88*d),minList=Math.round(280*d*text),minDetail=Math.round(360*d*text);
        int minHeight=Math.round(Math.max(480,272+128*text)*d);
        Box area=new Box(0,0,w,h),empty=new Box(0,0,0,0);Box first=null,second=null;boolean singleSafeRegion=false;
        if(fold!=null&&(fold.separating||fold.occluding)){
            int l=Math.max(0,Math.min(w,fold.left)),r=Math.max(l,Math.min(w,fold.right));
            int t=Math.max(0,Math.min(h,fold.top)),b=Math.max(t,Math.min(h,fold.bottom));
            if(fold.vertical&&fold.left>=0&&fold.right<=w&&fold.top<h&&fold.bottom>0){
                Box left=new Box(0,0,l,h),right=new Box(r,0,w,h);first=rtl?right:left;second=rtl?left:right;
                if(!(listDetail&&h>=minHeight&&first.width()>=minList+railWidth&&second.width()>=minDetail)){
                    area=left.width()>right.width()?left:right;first=second=null;singleSafeRegion=true;
                }
            }else if(!fold.vertical&&fold.top>=0&&fold.bottom<=h&&fold.left<w&&fold.right>0){
                Box top=new Box(0,0,w,t),bottom=new Box(0,b,w,h);area=top.height()>bottom.height()?top:bottom;
            }
        }
        boolean rail=area.width()>=Math.round(600*d)&&area.height()>=minHeight;
        Box nav=rail?(rtl?new Box(area.right-railWidth,area.top,area.right,area.bottom):new Box(area.left,area.top,area.left+railWidth,area.bottom)):empty;
        Box body=rail?(rtl?new Box(area.left,area.top,area.right-railWidth,area.bottom):new Box(area.left+railWidth,area.top,area.right,area.bottom)):area;
        boolean dual=listDetail&&!singleSafeRegion&&rail&&body.width()>=minList+minDetail;
        if(first!=null){
            Box list=rtl?new Box(first.left,first.top,first.right-railWidth,first.bottom):new Box(first.left+railWidth,first.top,first.right,first.bottom);
            return new Plan(area,nav,list,second,true,true);
        }
        if(!dual)return new Plan(area,nav,body,empty,rail,false);
        int listWidth=Math.min(body.width()-minDetail,Math.max(minList,Math.min(Math.round(360*d*text),Math.round(body.width()*.34f))));
        Box list=rtl?new Box(body.right-listWidth,body.top,body.right,body.bottom):new Box(body.left,body.top,body.left+listWidth,body.bottom);
        Box detail=rtl?new Box(body.left,body.top,list.left,body.bottom):new Box(list.right,body.top,body.right,body.bottom);
        return new Plan(area,nav,list,detail,rail,true);
    }
}
