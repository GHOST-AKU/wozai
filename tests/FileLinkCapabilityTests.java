package dev.ghost.nearbyim.core;

import java.util.*;
import java.util.function.*;

/** Old peers ignore the pre-existing reserved ENDPOINTS envelope; no new Noise bit is sent. */
public final class FileLinkCapabilityTests {
    public static void main(String[] args)throws Exception {
        Class<?> type;
        try{type=Class.forName("dev.ghost.nearbyim.core.FileLinkCapability");}
        catch(ClassNotFoundException missing){throw new AssertionError("Migration needs a legacy-safe capability exchange before new records",missing);}
        List<FileRoutePacket> fromLead=new ArrayList<>(),fromPeer=new ArrayList<>();String generation="a".repeat(64);
        var constructor=type.getConstructor(boolean.class,String.class,Consumer.class,Runnable.class);
        int[] enabled={0,0};Object lead=constructor.newInstance(true,generation,(Consumer<FileRoutePacket>)fromLead::add,(Runnable)()->enabled[0]++);
        Object peer=constructor.newInstance(false,generation,(Consumer<FileRoutePacket>)fromPeer::add,(Runnable)()->enabled[1]++);
        type.getMethod("start").invoke(lead);
        check(fromLead.size()==1&&enabled[0]==0,"Sending a challenge cannot authorize extension traffic");
        FileRoutePacket request=FileRouteCodec.decode(FileRouteCodec.encode(fromLead.get(0)));
        check(request.kind==FileRoutePacket.Kind.ENDPOINTS&&request.route==AutomaticFileRoutes.Route.BLUETOOTH&&UUID.fromString(request.nonce).version()==3,"Capability must use an old-decodable ignored envelope");
        var receive=type.getMethod("receive",FileRoutePacket.class);
        receive.invoke(lead,new FileRoutePacket(request.kind,UUID.nameUUIDFromBytes(new byte[]{1,2,3}).toString(),generation,request.route,false,List.of()));
        check(enabled[0]==0,"Wrong nonce enabled migration");
        receive.invoke(lead,new FileRoutePacket(request.kind,request.nonce,"b".repeat(64),request.route,false,List.of()));
        check(enabled[0]==0,"Wrong generation enabled migration");
        receive.invoke(peer,request);check(enabled[1]==1&&fromPeer.size()==1,"Matching peer did not echo capability");
        receive.invoke(lead,fromPeer.get(0));receive.invoke(lead,fromPeer.get(0));
        check(enabled[0]==1,"Duplicate echo re-enabled/restarted migration");
        check(type.getMethod("token").invoke(lead).equals(type.getMethod("token").invoke(peer)),"Parent-bound capability tokens differ");
        Object old=constructor.newInstance(true,generation,(Consumer<FileRoutePacket>)p->{},(Runnable)()->{throw new AssertionError("Silent old peer must not enable migration");});
        type.getMethod("start").invoke(old);check(!(Boolean)type.getMethod("supported").invoke(old),"Ignoring the safe challenge should preserve legacy operation");
        System.out.println("FILE capability: old-decodable challenge, nonce/generation scope, explicit echo, duplicate/silent-peer safety passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
