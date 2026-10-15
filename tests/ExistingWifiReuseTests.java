package dev.ghost.nearbyim.core;

import java.util.concurrent.*;

/** Existing endpoint hotspots must win without suppressing P2P on a routed LAN. */
public final class ExistingWifiReuseTests {
    public static void main(String[] args)throws Exception {
        for(boolean gatewayOnLeader:new boolean[]{true,false})try(AutomaticFileRoutesTests.Pair p=pair(gatewayOnLeader,!gatewayOnLeader,false)){
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Existing hotspot must reuse its encrypted parent");
            p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            check(p.a.platform.directCalled.getCount()==1&&p.b.platform.directCalled.getCount()==1,"Existing hotspot must not start native P2P");
            check(p.a.files.route().equals("wifi-direct")&&p.b.files.route().equals("wifi-direct"),"Both endpoints must identify the reused direct Wi-Fi path");
            check(p.a.files.diagnostics().contains("file_existing_wifi=confirmed_hotspot")&&p.b.files.diagnostics().contains("file_direct_outcome=not_needed_existing_hotspot"),"Both diagnostics must retain hotspot reuse evidence");
            AttachmentRecord record=new AttachmentRecord(AttachmentInfo.v2(java.util.UUID.randomUUID().toString(),"reuse.bin","application/octet-stream",4,"0".repeat(64),0),true,"transferring",2).withRoute(p.a.files.route());
            check(TransferPath.forAttachment("lan",true,AttachmentRecord.decode(record.encode()))==TransferPath.WIFI_DIRECT,"Hotspot reuse must persist a direct Wi-Fi bubble without a new history format");
            p.transfer();
            check(p.a.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN&&p.a.platform.directCalled.getCount()==1,"Next batch on the same hotspot must keep reusing it");
            p.a.nativePath=pathA(false);p.b.nativePath=pathB(false);
            check(p.a.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Changed OS evidence must re-evaluate direct eligibility");
        }
        for(boolean mismatch:new boolean[]{false,true})try(AutomaticFileRoutesTests.Pair p=pair(false,mismatch,mismatch)){
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Routed LAN or mismatched socket claims must still try P2P");p.transfer();
        }
        try(AutomaticFileRoutesTests.Pair p=pair(true,false,false)){
            p.b.ignorePath=true;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Older peer ignoring endpoint extension keeps original selection");p.transfer();
        }
        System.out.println("Existing hotspot reuse, recheck, routed LAN, mismatched endpoints and old-peer fallback passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static SocketPath pathA(boolean gateway){return new SocketPath("192.168.43.2",32123,"192.168.43.1",45678,gateway);}
    private static SocketPath pathB(boolean gateway){return new SocketPath("192.168.43.1",45678,"192.168.43.2",32123,gateway);}
    private static AutomaticFileRoutesTests.Pair pair(boolean a,boolean b,boolean mismatch)throws Exception{
        AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",true,false,false,true,pathA(a),mismatch?new SocketPath("192.168.43.9",45678,"192.168.43.2",32123,b):pathB(b));
        p.a.platform.directSuccess=p.b.platform.directSuccess=true;return p;
    }
}
