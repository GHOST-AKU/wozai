package dev.ghost.nearbyim.transport;

import java.lang.reflect.*;

/** Android may report a formed GO before tethering has assigned its address. */
public final class WifiDirectGroupReadinessTests {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Class<?> type;
        try{type=Class.forName("dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupReadiness");}
        catch(ClassNotFoundException missing){throw new AssertionError("A formed group without a DHCP address must wait within a fixed budget, not fail immediately",missing);}
        Object pending=create(type);
        check(state(type,pending,837,true,true,false,true).equals("WAIT_ADDRESS"),"837ms formed GO with null address waits for tethering");
        check(state(type,pending,1500,true,true,false,true).equals("WAIT_ADDRESS"),"Duplicate snapshots do not fail or extend the deadline");
        check(state(type,pending,2200,true,true,true,true).equals("READY"),"DHCP address arriving later permits interface verification");
        check(state(type,pending,10000,true,true,true,true).equals("READY"),"Healthy group snapshots do not expire after initial readiness");
        Object absent=create(type);
        check(state(type,absent,1000,true,true,false,true).equals("WAIT_ADDRESS"),"Missing address begins one bounded readiness wait");
        check(state(type,absent,8999,true,true,false,true).equals("WAIT_ADDRESS"),"Address can arrive anywhere within the existing 8s budget");
        check((long)type.getDeclaredMethod("remaining",long.class).invoke(absent,8999L)==1,"An independent timer uses the original deadline even if the next native query never returns");
        check(state(type,absent,9000,true,true,false,true).equals("EXPIRED"),"Repeated missing address ends at the original deadline");
        check(state(type,absent,9001,true,true,true,true).equals("EXPIRED"),"A late address cannot escape a readiness timeout");
        Object mixed=create(type);
        check(state(type,mixed,1000,true,false,true,true).equals("WAIT_ROLE"),"Non-atomic role queries can settle before a socket is opened");
        check(state(type,mixed,2000,false,false,false,false).equals("WAIT_GROUP"),"Incomplete query pair waits within the same budget");
        check(state(type,mixed,3000,true,true,true,false).equals("WAIT_INTERFACE"),"Interface assignment is part of readiness");
        check(state(type,mixed,9000,true,true,false,true).equals("EXPIRED"),"Changing incomplete fields never restarts the readiness budget");
        Object canceled=create(type);state(type,canceled,1000,true,true,false,true);type.getDeclaredMethod("stop").invoke(canceled);
        check(state(type,canceled,2000,true,true,true,true).equals("RETIRED"),"Address completion after cancellation cannot reopen the attempt");
        Object frequency=create(type);
        check(bandState(type,frequency,1000,false).equals("WAIT_FREQUENCY"),"A complete IP snapshot cannot prove the requested 5GHz band when frequency is unknown");
        check(bandState(type,frequency,8999,false).equals("WAIT_FREQUENCY"),"Duplicate unknown-frequency snapshots keep the original readiness deadline");
        check(bandState(type,frequency,8999,true).equals("READY"),"Actual 5GHz frequency can finish readiness before its deadline");
        Object neverReported=create(type);bandState(type,neverReported,1000,false);
        check(bandState(type,neverReported,9000,true).equals("EXPIRED"),"A late frequency cannot bypass the readiness timeout");
        System.out.println("P2P group IP readiness: "+checks+" checks passed");
    }
    private static Object create(Class<?> type)throws Exception{return type.getDeclaredConstructor(long.class).newInstance(8000L);}
    private static String state(Class<?> type,Object value,long now,boolean formed,boolean role,boolean address,boolean iface)throws Exception{
        return type.getDeclaredMethod("observe",long.class,boolean.class,boolean.class,boolean.class,boolean.class).invoke(value,now,formed,role,address,iface).toString();
    }
    private static String bandState(Class<?> type,Object value,long now,boolean frequency)throws Exception{
        try{return type.getDeclaredMethod("observe",long.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class).invoke(value,now,true,true,true,true,frequency).toString();}
        catch(NoSuchMethodException missing){throw new AssertionError("Group readiness must wait for actual band evidence",missing);}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
