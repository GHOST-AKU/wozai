package dev.ghost.nearbyim.core;
import java.util.*;
/** Authenticated control message for one parent-bound FILE route attempt. */
public final class FileRoutePacket {
    public enum Kind {REQUEST,BEGIN,ENDPOINTS,FAIL,SELECT,SELECT_ACK,BIND,BIND_ACK,REFRESH_REQUEST,RECHECK,RECHECK_ACK,BUSY,ABORT,DIRECT_CAPABILITIES,DIRECT_GROUP}
    public final Kind kind;
    public final String nonce,generation;
    public final AutomaticFileRoutes.Route route;
    public final boolean terminal;
    public final List<AutomaticFileRoutes.Endpoint> endpoints;
    public final DirectGroupCredentials directGroup;
    public final boolean supports5Ghz;
    public FileRoutePacket(Kind kind,String nonce,String generation,AutomaticFileRoutes.Route route,boolean terminal,List<AutomaticFileRoutes.Endpoint> endpoints){
        this(kind,nonce,generation,route,terminal,endpoints,null,false);
    }
    private FileRoutePacket(Kind kind,String nonce,String generation,AutomaticFileRoutes.Route route,boolean terminal,List<AutomaticFileRoutes.Endpoint> endpoints,DirectGroupCredentials group,boolean fiveGhz){
        this.kind=Objects.requireNonNull(kind);this.nonce=Objects.requireNonNull(nonce);this.generation=Objects.requireNonNull(generation);this.route=Objects.requireNonNull(route);this.terminal=terminal;
        this.endpoints=Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(endpoints)));
        if(!nonce.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!generation.matches("[0-9a-f]{64}")||this.endpoints.size()>8||this.endpoints.contains(null))throw new IllegalArgumentException("Invalid FILE control");
        if(kind!=Kind.ENDPOINTS&&!endpoints.isEmpty()||terminal&&kind!=Kind.FAIL)throw new IllegalArgumentException("Unexpected FILE fields");
        if((kind==Kind.DIRECT_GROUP)!=(group!=null)||kind!=Kind.DIRECT_CAPABILITIES&&fiveGhz||(kind==Kind.DIRECT_GROUP||kind==Kind.DIRECT_CAPABILITIES)&&route!=AutomaticFileRoutes.Route.WIFI_DIRECT)throw new IllegalArgumentException("Invalid Direct control");
        directGroup=group;supports5Ghz=fiveGhz;
    }
    public static FileRoutePacket directCapabilities(String nonce,String generation,boolean fiveGhz){return new FileRoutePacket(Kind.DIRECT_CAPABILITIES,nonce,generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,List.of(),null,fiveGhz);}
    public static FileRoutePacket directGroup(String nonce,String generation,DirectGroupCredentials group){return new FileRoutePacket(Kind.DIRECT_GROUP,nonce,generation,AutomaticFileRoutes.Route.WIFI_DIRECT,false,List.of(),Objects.requireNonNull(group),false);}
}
