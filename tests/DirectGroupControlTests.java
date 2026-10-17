package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/** Bounded, parent-encrypted credentials; never a discovery identity proof. */
public final class DirectGroupControlTests {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        Class<?> credentials;
        try{credentials=Class.forName("dev.ghost.nearbyim.core.DirectGroupCredentials");}
        catch(ClassNotFoundException missing){throw new AssertionError("Known-group credentials must bypass DNS-SD without exposing them in diagnostics",missing);}
        Constructor<?> constructor=credentials.getConstructor(String.class,String.class,int.class);
        String nonce=UUID.randomUUID().toString(),generation=String.join("",Collections.nCopies(64,"a"));
        Object group=constructor.newInstance("DIRECT-ab-wozai-12345678","0123456789abcdef0123456789abcdef",5180);
        Method packet=FileRoutePacket.class.getMethod("directGroup",String.class,String.class,credentials);
        FileRoutePacket encoded=(FileRoutePacket)packet.invoke(null,nonce,generation,group);
        FileRoutePacket decoded=FileRouteCodec.decode(FileRouteCodec.encode(encoded));
        Object restored=FileRoutePacket.class.getField("directGroup").get(decoded);
        check(group.equals(restored),"Group credentials survive encrypted-control codec exactly");
        check(decoded.nonce.equals(nonce)&&decoded.generation.equals(generation)&&decoded.route==AutomaticFileRoutes.Route.WIFI_DIRECT,"Credentials retain attempt, parent generation and route");
        check(FileRouteCodec.encode(encoded)[0]==2,"Credential controls use explicit extension version");
        FileRoutePacket capability=(FileRoutePacket)FileRoutePacket.class.getMethod("directCapabilities",String.class,String.class,boolean.class).invoke(null,nonce,generation,true);
        check(FileRoutePacket.class.getField("supports5Ghz").getBoolean(FileRouteCodec.decode(FileRouteCodec.encode(capability))),"Peer 5GHz hint is bounded control metadata");
        FileRoutePacket legacy=new FileRoutePacket(FileRoutePacket.Kind.BEGIN,nonce,generation,AutomaticFileRoutes.Route.LAN,false,List.of());
        check(FileRouteCodec.encode(legacy)[0]==1&&FileRouteCodec.decode(FileRouteCodec.encode(legacy)).kind==FileRoutePacket.Kind.BEGIN,"Existing control byte format stays version one");
        byte[] downgrade=FileRouteCodec.encode(encoded);downgrade[0]=1;rejected(()->FileRouteCodec.decode(downgrade));
        byte[] trailing=Arrays.copyOf(FileRouteCodec.encode(encoded),FileRouteCodec.encode(encoded).length+1);rejected(()->FileRouteCodec.decode(trailing));
        for(Object[] bad:new Object[][]{{"home-network","0123456789abcdef",5180},{"DIRECT-ab-ok","short",5180},{"DIRECT-ab-ok","0123456789abcdef",999},{"DIRECT-ab-ok","01234567\nabcdef",5180}}){
            try{constructor.newInstance(bad);throw new AssertionError("Unsafe group accepted");}catch(InvocationTargetException good){check(good.getCause() instanceof IllegalArgumentException,"Invalid group parameters rejected");}
        }
        Method random=credentials.getMethod("random");Set<String> names=new HashSet<>(),passwords=new HashSet<>();
        for(int i=0;i<32;i++){Object fresh=random.invoke(null);names.add((String)credentials.getField("ssid").get(fresh));passwords.add((String)credentials.getField("password").get(fresh));check(!fresh.toString().contains((String)credentials.getField("password").get(fresh)),"Default log representation reveals no password");}
        check(names.size()==32&&passwords.size()==32,"Each attempt gets independent random credentials");
        System.out.println("Direct group control: "+checks+" checks passed");
    }
    private interface Read{void run()throws Exception;}
    private static void rejected(Read read)throws Exception{try{read.run();throw new AssertionError("Invalid control accepted");}catch(IOException good){checks++;}}
}
