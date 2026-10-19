package dev.ghost.nearbyim.core;

import java.util.concurrent.*;

/** Real Noise FILE sessions around delayed/failed native connection boundaries. */
public final class AutomaticFileRouteDiagnosticsTests {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--file-wire")){fileWire();return;}
        check(AutomaticFileRoutes.class.getField("DIRECT_DISCOVERY_TIMEOUT_MS").getLong(null)==5_000,"An ACKed scan without a target has five seconds");
        check(AutomaticFileRoutes.DIRECT_DISCOVERY_PREPARE_TIMEOUT_MS==5_000,"Scan preparation is independently bounded");
        if(args.length>0&&args[0].equals("--slow-direct")){slowDirect();return;}
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",false,false)){
            check(p.a.files.diagnostics().contains("file_route_state=idle\n"),"An idle chat must not look like a stuck FILE negotiation");
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);
            String report=p.a.files.diagnostics();
            check(report.contains("file_route_state=ready\n")&&report.contains("file_transport=lan\n"),"Selected LAN must be reported ready");
            check(report.contains("file_direct_outcome=skipped\n")&&report.contains("file_direct_skip_reason=unsupported\n"),"A skipped Direct attempt must retain its prerequisite reason");
        }
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",true,false)){
            p.b.platform.delayDirect=true;
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);
            String report=p.a.files.diagnostics();
            check(report.contains("file_direct_outcome=failed\n")&&report.contains("file_route_last_failure=platform_error\n"),"LAN fallback must retain Direct failure evidence");
            p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            check(p.b.files.diagnostics().contains("file_direct_outcome=failed\n")&&p.b.files.diagnostics().contains("file_route_last_failure=peer_unavailable\n"),"A peer-triggered LAN fallback must end the local Direct attempt in diagnostics");
        }
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",true,false)){
            p.a.platform.delayDirect=p.b.platform.delayDirect=true;
            p.a.files.ensureReady();
            check(p.a.platform.directCalled.await(3,TimeUnit.SECONDS),"Direct must start");
            check(AutomaticFileRoutes.class.getMethod("pendingPath").invoke(p.a.files)==TransferPath.PENDING_WIFI_DIRECT,"Queued bubbles must show the ongoing Direct attempt, not an unknown path");
            check(p.b.platform.directCalled.await(3,TimeUnit.SECONDS)&&p.a.platform.attemptNonce.equals(p.b.platform.attemptNonce),"Both platforms receive the same FILE attempt correlation nonce");
        }
        for(var reason:AutomaticFileRoutes.DirectFailureReason.values())try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",true,false)){
            p.a.platform.directFailure=new AutomaticFileRoutes.DirectFailure(reason);p.b.platform.delayDirect=true;
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);
            check(p.a.files.diagnostics().contains("file_route_last_failure="+reason.name().toLowerCase(java.util.Locale.ROOT)+"\n"),"Native phase timeout must retain its specific cause: "+reason);
            p.transfer();
        }
        System.out.println("Automatic FILE route diagnostics passed");
        fileWire();
    }
    private static void fileWire()throws Exception {
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("bluetooth",true,false)) {
            p.a.platform.directSuccess=p.b.platform.directSuccess=true;
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            check(p.a.files.highBandwidth()&&p.b.files.highBandwidth(),"P2P FILE flow control must be independent of its Bluetooth chat parent");
            byte[] data=new byte[TransferLimits.DATA_BYTES];new java.util.Random(18).nextBytes(data);
            check(p.a.files.send(TransferPacket.data(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),p.a.wire.connectionGeneration(),data.length,0,data)),"Dedicated FILE send rejected");
            TransferPacket received=p.b.received.poll(5,TimeUnit.SECONDS);
            check(received!=null&&java.util.Arrays.equals(data,received.data),"Dedicated FILE data changed");
            String report=p.b.files.diagnostics();
            check(report.contains("file_wire_kind=dedicated\n"),"Diagnostics must distinguish the selected FILE wire from its Bluetooth chat parent");
            check(bytes(report,"file_wire_socket_read")>=data.length,"Actual P2P data socket reads must appear in FILE diagnostics");
            check(bytes(p.b.wire.diagnostics(),"socket_read")<data.length,"Fixture unexpectedly sent file bytes over the chat parent");
            check(bytes(p.a.files.diagnostics(),"file_wire_socket_write")>=data.length,"Actual P2P data socket writes must appear in FILE diagnostics");
        }
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",false,false)) {
            check(p.a.files.diagnostics().contains("file_wire_kind=none\n"),"Unselected FILE diagnostics must not invent a socket");
            check(!p.a.files.highBandwidth(),"Unselected route cannot claim Wi-Fi flow control");
            p.transfer();
            check(p.a.files.highBandwidth(),"Shared LAN files need Wi-Fi flow control");
            check(p.b.files.diagnostics().contains("file_wire_kind=chat\n"),"Reused LAN parent must be identified as shared");
            check(bytes(p.b.files.diagnostics(),"file_wire_socket_read")>0,"Shared FILE lane must expose its real socket counters");
        }
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("bluetooth",false,false)) {
            p.transfer();check(!p.a.files.highBandwidth(),"Actual Bluetooth fallback must retain its bounded flow control");
        }
        System.out.println("Selected FILE wire diagnostics cover dedicated P2P and shared LAN; parent counters remain separate");
    }
    private static long bytes(String report,String stage){
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?m)^"+stage+": count=\\d+, bytes=(\\d+),").matcher(report);
        check(match.find(),"Missing actual FILE wire stage: "+stage);return Long.parseLong(match.group(1));
    }
    private static void slowDirect()throws Exception {
        try(AutomaticFileRoutesTests.Pair p=new AutomaticFileRoutesTests.Pair("lan",true,false)){
            p.a.platform.delayDirect=p.b.platform.delayDirect=true;
            CompletableFuture<AutomaticFileRoutes.Route> result=p.a.files.ensureReady();
            check(p.a.platform.directCalled.await(3,TimeUnit.SECONDS)&&p.b.platform.directCalled.await(3,TimeUnit.SECONDS),"Both native Direct attempts must start");
            CompletableFuture.delayedExecutor(16,TimeUnit.SECONDS).execute(()->{
                try{
                    StreamConnection[] streams=AutomaticFileRoutesTests.sockets();
                    p.a.platform.opened.accept(new AutomaticFileRoutes.Opened(streams[0],false));
                    p.b.platform.opened.accept(new AutomaticFileRoutes.Opened(streams[1],true));
                }catch(Exception failure){result.completeExceptionally(failure);}
            });
            AutomaticFileRoutes.Route selected=result.get(25,TimeUnit.SECONDS);
            check(selected==AutomaticFileRoutes.Route.WIFI_DIRECT,"A Direct socket ready after 16 seconds must survive the native 50-second budget; selected "+selected);
            p.transfer();
        }
        System.out.println("Delayed Direct connection and encrypted FILE transfer passed");
    }
}
