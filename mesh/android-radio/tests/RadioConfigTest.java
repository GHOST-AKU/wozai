package org.nearbymesh.radio;
import java.util.*;
public final class RadioConfigTest {
    static Map<String,String> base() { Map<String,String> m=new HashMap<>(); m.put("mode","ble");m.put("label","A");m.put("neighbors","B");return m; }
    static void bad(Map<String,String> m) { try { RadioConfig.parse(m);throw new AssertionError("accepted invalid configuration"); }catch(IllegalArgumentException expected){} }
    public static void main(String[] args) {
        RadioConfig c=RadioConfig.parse(base()); if(!c.accepts("B")||c.accepts("A")||c.accepts("C")||c.accepts(null))throw new AssertionError();
        for(String mode:new String[]{"mixed","tcp","wifi-direct",""}) {Map<String,String> m=base();m.put("mode",mode);bad(m);}
        for(String n:new String[]{"A","B,B","B,C,D,E,F","","B,../x","B,é"}) {Map<String,String> m=base();m.put("neighbors",n);bad(m);}
        for(String port:new String[]{"0","1023","65536","1.5","-1"}) {Map<String,String> m=base();m.put("core_port",port);bad(m);}
        Map<String,String> m=base();m.put("mode","aware");bad(m);m.put("passphrase","12345678");if(!RadioConfig.parse(m).mode.equals("aware"))throw new AssertionError();
        m=base();m.put("passphrase","12345678");bad(m);m=base();m.put("unknown","true");bad(m);
        System.out.println("RadioConfigTest: PASS");
    }
}
