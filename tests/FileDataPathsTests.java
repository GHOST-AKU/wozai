package dev.ghost.nearbyim.core;
import java.util.*;
public final class FileDataPathsTests {
    public static void main(String[] args)throws Exception{
        String id=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),generation="a".repeat(64);
        AttachmentInfo info=AttachmentInfo.v2(id,"sample","application/octet-stream",2,null,1);
        FileDataPaths paths=new FileDataPaths();AttachmentRecord active=new AttachmentRecord(info,false,"transferring",1);
        paths.data(TransferPacket.data(id,source,generation,2,0,new byte[]{1}),false,"wifi-direct");
        paths.data(TransferPacket.control(TransferPacket.Kind.COMPLETE,id,source,generation,false,2,2,0,new byte[32],""),false,"lan");
        check(paths.decorate(active).route.equals("wifi-direct"),"Receipt traffic rewrote a DATA path");
        paths.data(TransferPacket.data(id,source,generation,2,1,new byte[]{2}),false,"lan");
        AttachmentRecord mixed=paths.decorate(active);
        check(mixed.route.equals("wifi-direct>lan"),"Actual P2P/LAN data segments were lost");
        check(AttachmentRecord.decode(mixed.encode()).equals(mixed),"Route history did not persist");
        check(TransferPath.forAttachment("bluetooth",true,mixed)==TransferPath.DIRECT_LAN,"Chat mode hid actual route history");
        System.out.println("FILE data paths: DATA-only route history, receipt isolation and durable mixed-path display passed");
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
