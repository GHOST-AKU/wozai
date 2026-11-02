package org.nearbymesh.radio;
import java.util.*;
public final class RadioConfigTest {
    static Map<String,String> base() { Map<String,String> m=new HashMap<>(); m.put("mode","ble");m.put("label","A");m.put("neighbors","B");return m; }
    static void bad(Map<String,String> m) { try { RadioConfig.parse(m);throw new AssertionError("accepted invalid configuration"); }catch(IllegalArgumentException expected){} }
    public static void main(String[] args) {
        // Public discovery must be an explicit API, independent of the restrictive lab parser.
        try {
            RadioConfig publicBle=(RadioConfig) RadioConfig.class.getMethod("publicMesh",String.class,String.class,int.class).invoke(null,"ble","n123abcd",39770);
            if(!publicBle.accepts("unknown")||!publicBle.accepts("B")||publicBle.accepts("n123abcd")||publicBle.accepts("../x")||publicBle.accepts("longlabel")||publicBle.accepts(null))throw new AssertionError("public discovery bounds");
            RadioConfig publicAware=(RadioConfig) RadioConfig.class.getMethod("publicMesh",String.class,String.class,int.class).invoke(null,"aware","n123abcd",39770);
            if(!"NearbyMesh-public-v1".equals(publicAware.passphrase)||publicAware.seconds!=3600||!publicAware.allowed.isEmpty())throw new AssertionError("public lobby policy");
            for(String[] invalid:new String[][]{{"mixed","n123abcd","39770"},{"ble","../x","39770"},{"ble","n123abcd","1023"},{"ble","n123abcd","65536"}}) {
                try { RadioConfig.class.getMethod("publicMesh",String.class,String.class,int.class).invoke(null,invalid[0],invalid[1],Integer.parseInt(invalid[2]));throw new AssertionError("invalid public configuration"); }
                catch(java.lang.reflect.InvocationTargetException expected) { if(!(expected.getCause() instanceof IllegalArgumentException))throw new AssertionError(expected); }
            }
        }catch(ReflectiveOperationException missing){throw new AssertionError("missing public Mesh discovery API",missing);}
        RadioConfig c=RadioConfig.parse(base()); if(!c.accepts("B")||c.accepts("A")||c.accepts("C")||c.accepts(null))throw new AssertionError();
        for(String mode:new String[]{"mixed","tcp","wifi-direct",""}) {Map<String,String> m=base();m.put("mode",mode);bad(m);}
        for(String n:new String[]{"A","B,B","B,C,D,E,F","","B,../x","B,é"}) {Map<String,String> m=base();m.put("neighbors",n);bad(m);}
        for(String port:new String[]{"0","1023","65536","1.5","-1"}) {Map<String,String> m=base();m.put("core_port",port);bad(m);}
        Map<String,String> m=base();m.put("mode","aware");bad(m);m.put("passphrase","12345678");if(!RadioConfig.parse(m).mode.equals("aware"))throw new AssertionError();
        m=base();m.put("passphrase","12345678");bad(m);m=base();m.put("unknown","true");bad(m);
        System.out.println("RadioConfigTest: PASS");
    }
}
