package dev.ghost.nearbyim.core;

import java.util.concurrent.*;
import static dev.ghost.nearbyim.core.AutomaticFileRoutesTests.*;

public final class DirectGroupRoutingTests {
    public static void main(String[] args)throws Exception {
        int before=checks;
        for(boolean localFive:new boolean[]{false,true})for(boolean peerFive:new boolean[]{false,true})try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.a.platform.fiveGhz=localFive;p.b.platform.fiveGhz=peerFive;
            AutomaticFileRoutes.Route expected=localFive&&peerFive?AutomaticFileRoutes.Route.WIFI_DIRECT:AutomaticFileRoutes.Route.LAN;
            check(p.b.files.ensureReady().get(5,TimeUnit.SECONDS)==expected,"An existing LAN is upgraded only when both peers support 5GHz");
            if(expected==AutomaticFileRoutes.Route.LAN){
                check(p.a.platform.groupCreates+p.b.platform.groupJoins==0,"No 2.4GHz group is created over a working LAN");
                check(p.a.files.diagnostics().contains("file_direct_skip_reason=5ghz_unavailable\n"),"Band capability skip remains visible");
            }else check(p.a.platform.requestedFiveGhz&&!p.a.platform.autoFallback,"LAN upgrade requests 5GHz without AUTO downgrade");
            p.transfer();
        }
        for(int frequency:new int[]{0,2412,6015})try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.a.platform.groupFrequency=frequency;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Unknown or non-5GHz group cannot replace the parent LAN");
            check(p.b.platform.groupJoins==0,"Rejected band is not advertised to the joiner");
            check(p.a.files.diagnostics().contains("file_route_last_failure="+(frequency==0?"frequency_unconfirmed":"band_mismatch")+"\n"),"Actual-band rejection has a specific diagnostic");p.transfer();
        }
        for(boolean peerFive:new boolean[]{false,true})try(Pair p=new Pair("bluetooth",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.b.platform.fiveGhz=peerFive;
            check(p.b.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Both capable peers must establish a known group without DNS-SD responses");
            check(p.a.platform.legacyOpens==0&&p.b.platform.legacyOpens==0,"Known-group route does not start legacy service discovery");
            check(p.a.platform.groupCreates==1&&p.b.platform.groupJoins==1,"Exactly one group owner and one joiner");
            check(p.a.platform.requestedFiveGhz==peerFive,"5GHz preference respects both peers' capabilities");
            check(p.a.platform.autoFallback,"Without a connected LAN, an AUTO fallback remains available");
            check(p.a.platform.attemptNonce.equals(p.b.platform.attemptNonce),"Group parameters retain the same authenticated attempt");
            p.transfer();check(p.a.wire.isReady()&&p.b.wire.isReady(),"Independent group file lane preserves encrypted parent chat");
        }
        for(int frequency:new int[]{0,2412})try(Pair p=new Pair("lan",true,false)){
            p.b.platform.groups=true;
            String probe=java.util.UUID.nameUUIDFromBytes(new byte[]{20,21,22}).toString(),generation=p.a.wire.connectionGeneration();
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.ENDPOINTS,probe,generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of()))&&p.a.groupCapabilities.poll(3,TimeUnit.SECONDS)!=null,"Peer capability probe authorizes an old coordinator's attempt");
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,probe,generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Old coordinator begins a negotiated group");
            check(p.a.wire.sendFileRoute(FileRoutePacket.directGroup(probe,generation,DirectGroupCredentials.random().withFrequency(frequency))),"Old coordinator advertises unknown or 2.4GHz group");
            check(p.b.groupControls.poll(3,TimeUnit.SECONDS)!=null,"Old group credentials decoded through Noise");drainActor(p.b.files);
            check(p.b.platform.groupJoins==0&&p.b.files.diagnostics().contains("file_route_last_failure="+(frequency==0?"frequency_unconfirmed":"band_mismatch")+"\n"),"Joiner refuses a legacy downgrade before changing Wi-Fi");
        }
        for(boolean leadCapable:new boolean[]{false,true})try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=leadCapable;p.b.platform.groups=!leadCapable;p.a.platform.directSuccess=p.b.platform.directSuccess=true;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Mixed capabilities preserve legacy native Direct");
            check(p.a.platform.groupCreates+p.b.platform.groupJoins==0,"Unconfirmed peer never receives group credentials");p.transfer();
        }
        try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.a.platform.createFailure=true;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Confirmed group failure falls back to LAN");
            check(p.b.platform.groupJoins==0,"Failed creator sends no group credentials");p.transfer();
        }
        try(Pair p=new Pair("lan",true,false,true)){
            p.a.platform.groups=p.b.platform.groups=true;
            try{p.a.files.ensureReady().get(5,TimeUnit.SECONDS);throw new AssertionError("Wrong root accepted on known group");}catch(ExecutionException expected){checks++;}
            check(p.a.files.route().isEmpty()&&p.a.wire.isReady(),"Known group still requires the approved root identity and never security-falls-back");
        }
        try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.b.ignorePath=true;p.a.platform.directSuccess=p.b.platform.directSuccess=true;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"A receiver with old probe semantics still uses version-four legacy Direct");
            check(p.a.platform.groupCreates==0&&p.a.platform.legacyOpens==1,"An unanswered compatibility probe never emits new credentials");p.transfer();
        }
        try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.b.platform.holdGroup=true;
            CompletableFuture<AutomaticFileRoutes.Route> ready=p.a.files.ensureReady();check(p.b.platform.groupJoinEntered.await(3,TimeUnit.SECONDS),"Native join held before socket delivery");
            FileRoutePacket original=p.b.groupControls.poll(3,TimeUnit.SECONDS);check(original!=null,"Original credentials crossed the real Noise parent");
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,original.nonce,original.generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Duplicate current BEGIN sent");
            check(p.a.wire.sendFileRoute(original),"Duplicate group control sent");check(p.b.groupControls.poll(3,TimeUnit.SECONDS)!=null,"Duplicate decoded");drainActor(p.b.files);
            check(p.b.platform.groupJoins==1&&!ready.isDone(),"Duplicate credentials cannot trigger a second join");
            check(p.a.wire.sendFileRoute(FileRoutePacket.directGroup(java.util.UUID.randomUUID().toString(),original.generation,DirectGroupCredentials.random())),"Stale-attempt credentials sent");
            check(p.b.groupControls.poll(3,TimeUnit.SECONDS)!=null,"Stale credentials decoded");drainActor(p.b.files);check(p.b.platform.groupJoins==1&&p.b.errors.isEmpty(),"Stale attempt cannot alter current native join");
            check(p.a.wire.sendFileRoute(FileRoutePacket.directGroup(original.nonce,original.generation,DirectGroupCredentials.random())),"Conflicting current credentials sent");
            check(p.b.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.files.ensureReady().isCompletedExceptionally(),"Conflicting credentials terminate FILE rather than changing a live group");
            check(p.b.wire.isReady(),"Invalid group control leaves chat available");
        }
        try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.b.platform.holdGroup=true;CompletableFuture<AutomaticFileRoutes.Route> ready=p.a.files.ensureReady();
            check(p.b.platform.groupJoinEntered.await(3,TimeUnit.SECONDS),"Cancelable group attempt reached native join");
            java.util.function.Consumer<AutomaticFileRoutes.Opened> late=p.a.platform.groupOpened;p.a.files.abort();
            try{ready.get(3,TimeUnit.SECONDS);throw new AssertionError("Canceled group completed");}catch(ExecutionException expected){checks++;}
            ClosedConnection connection=new ClosedConnection();late.accept(new AutomaticFileRoutes.Opened(connection,true));check(connection.closed.await(3,TimeUnit.SECONDS),"Late native group socket after cancel is disposed");
            check(p.a.wire.isReady(),"Canceling group transfer keeps parent identity/session");
        }
        try(Pair p=new Pair("lan",true,false)){
            p.a.platform.groups=p.b.platform.groups=true;p.b.platform.holdGroup=true;
            p.a.files.ensureReady();check(p.b.platform.groupJoinEntered.await(3,TimeUnit.SECONDS),"Replay scenario reached native join");
            FileRoutePacket original=p.b.groupControls.poll(3,TimeUnit.SECONDS);check(original!=null,"Canceled group credentials retained for replay");
            p.a.files.abort();check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Cancel retired both group attempts");
            p.a.errors.clear();p.b.errors.clear();
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,original.nonce,original.generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Canceled BEGIN replay sent");
            check(p.a.wire.sendFileRoute(original),"Canceled credentials replay sent");
            check(p.b.groupControls.poll(3,TimeUnit.SECONDS)!=null,"Replayed controls decoded");drainActor(p.b.files);
            check(p.b.platform.groupJoins==1&&p.b.files.pendingPath()==TransferPath.SELECTING,"Canceled BEGIN and credentials cannot restart native join");
            check(p.b.errors.isEmpty(),"Retired group controls do not fail a subsequent batch");
            p.b.platform.holdGroup=false;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Fresh negotiation after cancellation can establish a new group");p.transfer();
        }
        try(Pair p=new Pair("lan",true,false)){
            p.b.platform.groups=true;
            String probe=java.util.UUID.nameUUIDFromBytes(new byte[]{10,11,12}).toString();
            FileRoutePacket request=new FileRoutePacket(FileRoutePacket.Kind.ENDPOINTS,probe,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of());
            check(p.a.wire.sendFileRoute(request)&&p.a.groupCapabilities.poll(3,TimeUnit.SECONDS)!=null,"Capability acknowledged before BEGIN");
            p.b.files.abort();check(p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Cancel before BEGIN revoked responder grant");p.b.errors.clear();
            check(p.a.wire.sendFileRoute(request),"Canceled capability probe replay sent");
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,probe,request.generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Canceled pre-BEGIN nonce replay sent");
            check(p.a.wire.sendFileRoute(FileRoutePacket.directGroup(probe,request.generation,DirectGroupCredentials.random())),"Canceled pre-BEGIN credentials sent");
            check(p.b.groupControls.poll(3,TimeUnit.SECONDS)!=null,"Pre-BEGIN replay decoded");drainActor(p.b.files);
            check(p.b.platform.groupJoins==0&&p.b.errors.isEmpty(),"Canceled probe cannot reauthorize a never-entered group");
        }
        try(Pair p=new Pair("lan",true,false)){
            p.b.platform.groups=true;
            String probe=java.util.UUID.nameUUIDFromBytes(new byte[]{4,5,6}).toString(),other=java.util.UUID.nameUUIDFromBytes(new byte[]{7,8,9}).toString();
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.ENDPOINTS,probe,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Capability probe sent without starting a route");
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,other,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Different nonce BEGIN sent after capability probe");
            check(p.b.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.platform.groupJoins==0,"Capability authorization is bound to its exact negotiation nonce");
        }
        try(Pair p=new Pair("lan",true,false)){
            String token=java.util.UUID.nameUUIDFromBytes(new byte[]{1,2,3}).toString();
            check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BEGIN,token,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.WIFI_DIRECT,false,java.util.List.of())),"Unnegotiated known-group BEGIN sent");
            check(p.b.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.platform.groupJoins==0,"Known-group mode requires prior peer capability negotiation");
        }
        System.out.println("Direct group routes: "+(checks-before)+" checks passed");
    }
}
