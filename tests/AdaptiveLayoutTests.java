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
        System.out.println("Adaptive layout: "+checks+" window/fold/RTL/height checks passed");
    }
    private static AdaptiveLayout.Plan plan(int w,int h){return AdaptiveLayout.compute(w,h,1,1,false,true,null);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
