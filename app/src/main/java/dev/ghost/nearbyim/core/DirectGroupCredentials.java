package dev.ghost.nearbyim.core;

import java.security.SecureRandom;
import java.util.Objects;

/** Ephemeral network parameters carried only inside the approved parent Noise session. */
public final class DirectGroupCredentials {
    public final String ssid,password;
    public final int frequency;
    public DirectGroupCredentials(String ssid,String password,int frequency){
        if(ssid==null||!ssid.matches("DIRECT-[A-Za-z0-9]{2}[A-Za-z0-9_-]{1,23}")||password==null||!password.matches("[\\x20-\\x7e]{8,63}")
                ||frequency!=0&&!(frequency>=2400&&frequency<=2500||frequency>=4900&&frequency<=7125))throw new IllegalArgumentException("Invalid temporary P2P group");
        this.ssid=ssid;this.password=password;this.frequency=frequency;
    }
    public static DirectGroupCredentials random(){
        SecureRandom random=new SecureRandom();byte[] name=new byte[5],secret=new byte[16];random.nextBytes(name);random.nextBytes(secret);
        String hex=AttachmentInfo.hex(name);return new DirectGroupCredentials("DIRECT-"+hex.substring(0,2)+"-wozai-"+hex.substring(2),AttachmentInfo.hex(secret),0);
    }
    public DirectGroupCredentials withFrequency(int value){return new DirectGroupCredentials(ssid,password,value);}
    public boolean equals(Object other){if(!(other instanceof DirectGroupCredentials))return false;DirectGroupCredentials value=(DirectGroupCredentials)other;return ssid.equals(value.ssid)&&password.equals(value.password)&&frequency==value.frequency;}
    public int hashCode(){return Objects.hash(ssid,password,frequency);}
}
