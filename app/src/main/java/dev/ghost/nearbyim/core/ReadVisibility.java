package dev.ghost.nearbyim.core;

import java.util.*;

/** Stable foreground visibility is evidence of display, never proof of human comprehension. */
public final class ReadVisibility {
    public static final long DWELL_NANOS=250_000_000L;
    private String peer;
    private final Map<String,Long> since=new HashMap<>();
    private final Set<String> emitted=new HashSet<>();
    public List<String> update(String peer,Collection<String> visible,boolean foreground,long now) {
        if(!foreground||peer==null){clear();return List.of();}
        if(!Objects.equals(this.peer,peer)){clear();this.peer=peer;}
        Set<String> live=new HashSet<>(visible);since.keySet().retainAll(live);emitted.retainAll(live);
        List<String> ready=new ArrayList<>();
        for(String id:visible){Long began=since.putIfAbsent(id,now);if(began!=null&&now-began>=DWELL_NANOS&&!emitted.contains(id)&&ready.size()<32){ready.add(id);emitted.add(id);}}
        return ready;
    }
    public void clear(){peer=null;since.clear();emitted.clear();}
    public static boolean visible(double top,double height,double viewportHeight){
        if(!Double.isFinite(top)||!Double.isFinite(height)||!Double.isFinite(viewportHeight)||height<=0||viewportHeight<=0)return false;
        double overlap=Math.max(0,Math.min(top+height,viewportHeight)-Math.max(0,top));
        return overlap>=Math.min(height/2,viewportHeight/2);
    }
}
