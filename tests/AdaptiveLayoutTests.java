package dev.ghost.nearbyim.core;

/** Real pixel rectangles: breakpoints, font/height constraints, RTL and reported hinge bounds. */
public final class AdaptiveLayoutTests {
    private static int checks;
    public static void main(String[] args){
        check(!plan(599,800).rail(),"599dp uses bottom navigation");check(plan(600,800).rail(),"600dp uses rail");
        check(!plan(727,800).dual(),"Insufficient pane width stays single");
        check(plan(728,800).dual()&&plan(800,600).dual(),"Wide foldables use both panes once their actual minimum widths fit");
        check(!plan(1200,479).dual()&&!plan(1200,479).rail(),"Short landscape/IME space must not force panes");
        check(!AdaptiveLayout.compute(840,800,1,2,false,true,null).dual(),"Large text needs usable pane minimums");
        check(!AdaptiveLayout.compute(1200,800,1,2,false,true,null).dual(),"Two-times text must not be measured as 1.5-times text");
        check(AdaptiveLayout.compute(1680,1600,2,1,false,true,null).dual(),"Breakpoints use dp rather than physical pixels");
        var p=plan(1000,800);check(p.list().right()<=p.detail().left(),"Panes do not overlap");check(p.detail().width()>=360,"Detail readable");
        var rtl=AdaptiveLayout.compute(1000,800,1,1,true,true,null);check(rtl.navigation().left()>rtl.list().left()&&rtl.list().left()>rtl.detail().left(),"RTL mirrors rail and panes");
        var hinge=new AdaptiveLayout.Fold(480,0,520,800,true,true,true);
        p=AdaptiveLayout.compute(1000,800,1,1,false,true,hinge);check(p.dual()&&p.list().right()==480&&p.detail().left()==520,"Reported separating hinge divides panes");
        p=AdaptiveLayout.compute(1000,800,1,1,false,false,hinge);check(!p.dual()&&p.area().left()==520,"Single-page tools use one safe region");
        p=AdaptiveLayout.compute(1000,800,1,1,false,true,new AdaptiveLayout.Fold(160,0,180,800,true,true,true));check(!p.dual()&&p.area().left()==180,"An unusable narrow hinge side cannot force two panes");
        p=AdaptiveLayout.compute(1000,900,1,1,false,true,new AdaptiveLayout.Fold(0,400,1000,440,false,true,false));check(!p.dual()&&p.area().top()==440,"Tabletop uses unobstructed region");
        p=AdaptiveLayout.compute(1000,800,1,1,false,true,new AdaptiveLayout.Fold(500,0,500,800,true,false,false));check(p.dual()&&p.detail().left()!=500,"A flat non-separating crease doesn't constrain layout");
        check(plan(0,0).area().width()==0,"Unmeasured window is safe");
        for(float scale:new float[]{1,1.3f,2,3})for(int width:new int[]{727,728,800,1000,1400,2200}){
            p=AdaptiveLayout.compute(width,800,1,scale,false,true,null);
            if(p.dual())check(p.list().width()>=Math.round(280*scale)&&p.detail().width()>=Math.round(360*scale),"Both actual pane minimums fit at font scale "+scale);
        }
        for(boolean direction:new boolean[]{false,true})for(int w:new int[]{240,599,600,839,840,1200,1600})for(int h:new int[]{240,480,800}){
            p=AdaptiveLayout.compute(w,h,1,1,direction,true,null);
            for(var rect:new AdaptiveLayout.Box[]{p.area(),p.list(),p.detail(),p.navigation()})check(rect.left()>=0&&rect.top()>=0&&rect.right()<=w&&rect.bottom()<=h&&rect.width()>=0&&rect.height()>=0,"All geometry stays inside current window");
        }
        adjustableSplit();
        System.out.println("Adaptive layout: "+checks+" window/fold/RTL/height checks passed");
    }
    private static void adjustableSplit(){
        try{
            var compute=AdaptiveLayout.class.getMethod("compute",int.class,int.class,float.class,float.class,boolean.class,boolean.class,AdaptiveLayout.Fold.class,float.class);
            for(boolean rtl:new boolean[]{false,true}){
                var centered=(AdaptiveLayout.Plan)compute.invoke(null,1200,800,1f,1f,rtl,true,null,.5f);
                check((rtl?centered.list().left():centered.list().right())==600,"Divider centers on the window, not the rail-excluded body");
                for(float fraction:new float[]{-1,0,.2f,.8f,1,2,Float.NaN}){
                    var p=(AdaptiveLayout.Plan)compute.invoke(null,1000,800,1f,1f,rtl,true,null,fraction);
                    check(p.list().width()>=280&&p.detail().width()>=360,"Dragged split preserves both pane minimums");
                }
            }
            var hinge=new AdaptiveLayout.Fold(480,0,520,800,true,true,true);
            var p=(AdaptiveLayout.Plan)compute.invoke(null,1000,800,1f,1f,false,true,hinge,.7f);
            check(p.list().right()==480&&p.detail().left()==520,"User preference cannot move a separating hinge");
            var snap=AdaptiveLayout.class.getMethod("snapDivider",float.class,float.class,float.class,boolean.class);
            check((Float)snap.invoke(null,591f,600f,1f,false)==600f,"Dragging near the center snaps exactly");
            check((Float)snap.invoke(null,622f,600f,1f,true)==600f,"Snap hysteresis avoids center jitter");
            check((Float)snap.invoke(null,635f,600f,1f,true)==635f,"Dragging farther releases the center snap");
            var anchors=AdaptiveLayout.class.getMethod("snapDivider",float.class,float.class,float.class,float.class,float.class);
            check((Float)anchors.invoke(null,407f,640f,400f,1f,Float.NaN)==400f,"Original adaptive split is also a snap point");
            check((Float)anchors.invoke(null,422f,640f,400f,1f,400f)==400f,"Original split has the same release hysteresis");
            check((Float)anchors.invoke(null,435f,640f,400f,1f,400f)==435f,"Original split releases beyond its radius");
            check((Float)anchors.invoke(null,632f,640f,400f,1f,400f)==640f,"Dragging from the original split can snap to center");
            check((Float)anchors.invoke(null,510f,512f,500f,1f,Float.NaN)==512f,"Nearby anchors select the nearest one");
            check((Float)anchors.invoke(null,512f,512f,500f,1f,500f)==512f,"Nearby default latch must allow reaching center");
            check((Float)anchors.invoke(null,500f,512f,500f,1f,512f)==500f,"Nearby center latch must allow reaching default");
            check((Float)anchors.invoke(null,506f,512f,500f,1f,500f)==500f,"Equal-distance motion keeps the current anchor");
            check((Float)anchors.invoke(null,407f,Float.NaN,400f,1f,Float.NaN)==400f,"Default snap still works when center is constrained");
        }catch(ReflectiveOperationException missing){throw new AssertionError("Adaptive panes have no adjustable, snapping divider",missing);}
    }
    private static AdaptiveLayout.Plan plan(int w,int h){return AdaptiveLayout.compute(w,h,1,1,false,true,null);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
