package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.util.*;

/** Actual DATA paths only. Control, probe and receipt traffic cannot rewrite history. */
final class FileDataPaths {
    private final Map<String,String> paths=new LinkedHashMap<>();
    private static String key(String id,boolean outgoing){return id+":"+outgoing;}
    synchronized void data(TransferPacket packet,boolean outgoing,String route){
        if(packet.kind!=TransferPacket.Kind.DATA||packet.data.length==0||route.isEmpty())return;
        String key=key(packet.transferId,outgoing),before=paths.getOrDefault(key,"");
        List<String> history=new ArrayList<>(before.isEmpty()?List.of():Arrays.asList(before.split(">")));
        if(!history.contains(route))history.add(route);
        paths.put(key,String.join(">",history));while(paths.size()>512)paths.remove(paths.keySet().iterator().next());
    }
    synchronized void remember(AttachmentRecord record){
        if(record.route.isEmpty())return;
        String key=key(record.info.id,record.outgoing),current=paths.getOrDefault(key,"");
        List<String> history=new ArrayList<>(Arrays.asList(record.route.split(">")));
        if(!current.isEmpty())for(String path:current.split(">"))if(!history.contains(path))history.add(path);
        paths.put(key,String.join(">",history));while(paths.size()>512)paths.remove(paths.keySet().iterator().next());
    }
    synchronized AttachmentRecord decorate(AttachmentRecord record)throws IOException{
        String key=key(record.info.id,record.outgoing),history=paths.get(key);
        if(history==null){if(!record.route.isEmpty())paths.put(key,record.route);return record;}
        return record.withRoute(history);
    }
}
