package dev.ghost.nearbyim.core;

import java.util.concurrent.*;

/** Real Noise FILE sessions around delayed/failed native connection boundaries. */
public final class AutomaticFileRouteDiagnosticsTests {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        check(AutomaticFileRoutes.class.getField("DIRECT_DISCOVERY_TIMEOUT_MS").getLong(null)==5_000,"Advertising without a target must fall back within five seconds");
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
        }
        System.out.println("Automatic FILE route diagnostics passed");
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
