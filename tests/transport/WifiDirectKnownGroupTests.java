package dev.ghost.nearbyim.transport;

import java.lang.reflect.*;

public final class WifiDirectKnownGroupTests {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Class<?> type;
        try{type=Class.forName("dev.ghost.nearbyim.transport.WifiDirectPolicy$KnownGroup");}
        catch(ClassNotFoundException missing){throw new AssertionError("Explicit groups need an ACK-, role- and cancellation-bound policy",missing);}
        Object owner=type.getDeclaredConstructor(String.class,boolean.class,boolean.class).newInstance("DIRECT-ab-wozai-12345678",true,true);
        check(!call(type,owner,"matches",new Class[]{String.class,boolean.class},"DIRECT-ab-wozai-12345678",true),"A matching name before native ACK grants no socket authority");
        check(call(type,owner,"begin"),"First native command allowed");check(!call(type,owner,"begin"),"Unknown ACK fences duplicate commands");check(!call(type,owner,"fallback"),"Unknown ACK cannot trigger an AUTO retry");
        call(type,owner,"ack",new Class[]{boolean.class},false);check(call(type,owner,"fallback"),"Confirmed 5GHz failure allows one AUTO retry");
        check(!call(type,owner,"prefers5Ghz"),"AUTO retry drops the failed band restriction");check(call(type,owner,"begin"),"AUTO command permitted only after confirmed failure");
        call(type,owner,"ack",new Class[]{boolean.class},true);
        check(call(type,owner,"matches",new Class[]{String.class,boolean.class},"DIRECT-ab-wozai-12345678",true),"ACK and exact fresh SSID/owner role admit our group");
        check(!call(type,owner,"matches",new Class[]{String.class,boolean.class},"DIRECT-ab-foreign",true),"Foreign group name rejected");
        check(!call(type,owner,"matches",new Class[]{String.class,boolean.class},"DIRECT-ab-wozai-12345678",false),"Wrong group role rejected");
        check(call(type,owner,"announce")&&!call(type,owner,"announce"),"Only the owner announces credentials once");
        type.getDeclaredMethod("stop").invoke(owner);
        check(!call(type,owner,"matches",new Class[]{String.class,boolean.class},"DIRECT-ab-wozai-12345678",true)&&!call(type,owner,"begin"),"Retired group cannot reopen sockets or issue commands");
        Object client=type.getDeclaredConstructor(String.class,boolean.class,boolean.class).newInstance("DIRECT-ab-wozai-12345678",false,false);
        check(call(type,client,"begin"),"Join command allowed");call(type,client,"ack",new Class[]{boolean.class},false);
        check(!call(type,client,"fallback")&&!call(type,client,"announce"),"Joiner cannot invent another group or advertise a password");
        Object late=type.getDeclaredConstructor(String.class,boolean.class,boolean.class).newInstance("DIRECT-ab-wozai-12345678",true,true);
        call(type,late,"begin");type.getDeclaredMethod("stop").invoke(late);
        check(!call(type,late,"ack",new Class[]{boolean.class},true)&&!call(type,late,"announce"),"Late native success after cancellation grants no group authority");
        System.out.println("Known group policy: "+checks+" checks passed");
    }
    private static boolean call(Class<?> type,Object value,String method,Object... unused)throws Exception{return (boolean)type.getDeclaredMethod(method).invoke(value);}
    private static boolean call(Class<?> type,Object value,String method,Class<?>[] types,Object... values)throws Exception{return (boolean)type.getDeclaredMethod(method,types).invoke(value,values);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
