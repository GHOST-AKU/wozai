package dev.ghost.nearbyim.core;
import java.util.*;
public final class ReadVisibilityTests {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    public static void main(String[] args){
        ReadVisibility r=new ReadVisibility();long dwell=ReadVisibility.DWELL_NANOS;
        check(r.update("a",List.of("1"),true,0).isEmpty(),"Layout alone marked read");
        check(r.update("a",List.of("1"),true,dwell-1).isEmpty(),"Fast pass marked read");
        check(r.update("a",List.of("1"),true,dwell).equals(List.of("1")),"Stable visibility lost");
        check(r.update("a",List.of("1"),true,dwell*2).isEmpty(),"Repeated visible polls rewrote disk");
        r.update("a",List.of("2"),true,0);r.update("a",List.of("2"),false,dwell);
        check(r.update("a",List.of("2"),true,dwell*2).isEmpty(),"Focus loss retained dwell");
        check(r.update("b",List.of("2"),true,dwell*3).isEmpty(),"Conversation switch retained dwell");
        check(!ReadVisibility.visible(101,10,100)&&!ReadVisibility.visible(-20,10,100),"Offscreen marked visible");
        check(!ReadVisibility.visible(99,10,100)&&ReadVisibility.visible(95,10,100),"Minimum visible area wrong");
        check(ReadVisibility.visible(-500,1000,100),"Long message cannot ever become visible");
        check(!ReadVisibility.visible(0,0,100)&&!ReadVisibility.visible(0,10,0)&&!ReadVisibility.visible(Double.NaN,10,100),"Unlaid/invalid geometry marked read");
        List<String> many=new ArrayList<>();for(int i=0;i<50;i++)many.add("m"+i);
        r.clear();r.update("a",many,true,0);check(r.update("a",many,true,dwell).size()==32&&r.update("a",many,true,dwell+1).size()==18,"Visibility batch limit loses remaining rows");
        System.out.println("ReadVisibilityTests: "+count+" checks passed");
    }
}
