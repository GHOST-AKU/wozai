package org.nearbymesh.radio;

import java.util.*;

/** Lab labels restrict topology; cryptographic identity is authenticated only by the Rust core. */
public final class RadioConfig {
    public final String mode,label,passphrase;
    public final Set<String> allowed;
    public final int corePort,seconds;
    private final boolean publicDiscovery;
    private RadioConfig(String mode,String label,Set<String> allowed,int port,int seconds,String passphrase) {
        this(mode,label,allowed,port,seconds,passphrase,false);
    }
    private RadioConfig(String mode,String label,Set<String> allowed,int port,int seconds,String passphrase,boolean publicDiscovery) {
        this.mode=mode;this.label=label;this.allowed=Collections.unmodifiableSet(allowed);
        this.corePort=port;this.seconds=seconds;this.passphrase=passphrase;
        this.publicDiscovery=publicDiscovery;
    }
    public boolean accepts(String remote) {return remote!=null&&remote.matches("[A-Za-z0-9_-]{1,8}")&&!label.equals(remote)&&(publicDiscovery||allowed.contains(remote));}
    /** Public labels are discovery hints only; the Rust core authenticates signed pseudonyms. */
    public static RadioConfig publicMesh(String mode,String label,int corePort) {
        if(!"ble".equals(mode)&&!"aware".equals(mode))throw new IllegalArgumentException("single_radio_required");
        if(label==null||!label.matches("[A-Za-z0-9_-]{1,8}"))throw new IllegalArgumentException("label_bound");
        if(corePort<1024||corePort>65535)throw new IllegalArgumentException("integer_bound");
        // This public lobby value is neither a membership secret nor end-to-end encryption.
        return new RadioConfig(mode,label,Collections.emptySet(),corePort,3600,
                "aware".equals(mode)?"NearbyMesh-public-v1":null,true);
    }
    public static RadioConfig parse(Map<String,String> values) {
        Set<String> known=new HashSet<>(Arrays.asList("mode","label","neighbors","core_port","seconds","passphrase"));
        if(!known.containsAll(values.keySet()))throw new IllegalArgumentException("unknown_argument");
        String mode=values.get("mode"),label=values.get("label");
        if(!"ble".equals(mode)&&!"aware".equals(mode))throw new IllegalArgumentException("single_radio_required");
        if(label==null||!label.matches("[A-Za-z0-9_-]{1,8}"))throw new IllegalArgumentException("label_bound");
        String[] neighbors=values.getOrDefault("neighbors","").split(",",-1);
        Set<String> allowed=new HashSet<>();
        for(String peer:neighbors)if(!peer.matches("[A-Za-z0-9_-]{1,8}")||peer.equals(label)||!allowed.add(peer))throw new IllegalArgumentException("neighbor_bound");
        if(allowed.size()>4)throw new IllegalArgumentException("neighbor_limit");
        int port=number(values.getOrDefault("core_port","39770"),1024,65535);
        int seconds=number(values.getOrDefault("seconds","120"),5,3600);
        String passphrase=values.get("passphrase");
        if("aware".equals(mode)) {
            if(passphrase==null||!passphrase.matches("[\\x20-\\x7e]{8,63}"))throw new IllegalArgumentException("aware_passphrase_required");
        }else if(passphrase!=null)throw new IllegalArgumentException("unused_passphrase");
        return new RadioConfig(mode,label,allowed,port,seconds,passphrase);
    }
    private static int number(String value,int min,int max) {
        if(value==null||!value.matches("[0-9]{1,5}"))throw new IllegalArgumentException("integer_bound");
        int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException("integer_bound");return n;
    }
}
