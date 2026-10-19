package dev.ghost.nearbyim.core;

import java.util.*;
import java.util.function.*;

/** A reserved old-format envelope confirms extensions without adding a rejected Noise bit. */
public final class FileLinkCapability {
    private final boolean lead;private final String generation;private final Consumer<FileRoutePacket> send;private final Runnable enabled;
    private String token;private boolean supported;
    public FileLinkCapability(boolean lead,String generation,Consumer<FileRoutePacket> send,Runnable enabled){this.lead=lead;this.generation=Objects.requireNonNull(generation);this.send=Objects.requireNonNull(send);this.enabled=Objects.requireNonNull(enabled);}
    public void start(){
        if(!lead||token!=null)return;
        token=UUID.nameUUIDFromBytes((generation+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();send.accept(envelope(token));
    }
    public boolean receive(FileRoutePacket packet){
        if(packet==null||packet.kind!=FileRoutePacket.Kind.ENDPOINTS||packet.route!=AutomaticFileRoutes.Route.BLUETOOTH||UUID.fromString(packet.nonce).version()!=3)return false;
        if(!generation.equals(packet.generation)||!packet.endpoints.isEmpty()||packet.terminal)return true;
        if(lead){if(!supported&&Objects.equals(token,packet.nonce)){supported=true;enabled.run();}}
        else if(!supported){token=packet.nonce;supported=true;enabled.run();send.accept(envelope(token));}
        else if(Objects.equals(token,packet.nonce))send.accept(envelope(token));
        return true;
    }
    private FileRoutePacket envelope(String token){return new FileRoutePacket(FileRoutePacket.Kind.ENDPOINTS,token,generation,AutomaticFileRoutes.Route.BLUETOOTH,false,List.of());}
    public boolean supported(){return supported;}
    public String token(){return token;}
}
