package dev.ghost.nearbyim.storage;

import java.lang.reflect.Method;

/** GO/GC socket roles must never grant consent or change the selected recipient. */
public final class ConnectionRoleTests {
    private static Method decide,requested;
    private static int checks;
    public static void main(String[] args)throws Exception {
        try {
            Class<?> type=Class.forName("dev.ghost.nearbyim.core.ConnectionRole");
            decide=type.getMethod("decide",boolean.class,boolean.class,boolean.class,boolean.class);
            requested=type.getMethod("userRequested");
        }catch(ReflectiveOperationException absent){throw new AssertionError("No separation of user consent from Wi-Fi group/socket role",absent);}
        // Explicit selection needs no second local prompt whether this phone is GO or GC.
        for(boolean tcpIncoming:new boolean[]{true,false}) {
            Object selected=role(true,tcpIncoming,true,true,"USER_REQUESTED");
            TrustPolicy selectedTrust=new TrustPolicy();
            check(selectedTrust.begin("selected-device","proven-key",null,(boolean)requested.invoke(selected),true).decision==TrustPolicy.Decision.APPROVE,"Selected P2P GO/GC loses user's consent");
            check(selectedTrust.begin("selected-device","new-key","old-key",(boolean)requested.invoke(selected),true).decision==TrustPolicy.Decision.IDENTITY_CHANGED,"Selection overrides a changed pinned identity");
            // A remote request needs consent even when local TCP must initiate because it is GC.
            Object passive=role(true,tcpIncoming,false,false,"PEER_REQUESTED");
            TrustPolicy passiveTrust=new TrustPolicy();
            check(passiveTrust.begin("new-device","proven-key",null,(boolean)requested.invoke(passive),true).decision==TrustPolicy.Decision.ASK,"Passive P2P TCP initiator bypasses chat consent");
            role(true,tcpIncoming,true,false,"DISCARD");
        }
        role(false,true,false,false,"PEER_REQUESTED");
        role(false,true,true,true,"DISCARD");
        role(false,false,false,false,"DISCARD");
        role(false,false,true,false,"DISCARD");
        role(false,false,true,true,"USER_REQUESTED");
        System.out.println("Connection intent: "+checks+" checks passed (GO/GC consent, busy/late sockets, pinned identities)");
    }
    private static Object role(boolean direct,boolean tcpIncoming,boolean attempt,boolean matches,String expected)throws Exception {
        Object role=decide.invoke(null,direct,tcpIncoming,attempt,matches);
        check(expected.equals(role.toString()),"Expected "+expected+" but got "+role);return role;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
