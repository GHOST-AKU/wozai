package dev.ghost.nearbyim.core;

import java.net.*;

/** Native socket endpoints plus a local OS observation, never a discovery hint. */
public final class SocketPath {
    public final InetSocketAddress local,peer;
    public final boolean peerIsWifiGateway;
    public SocketPath(String local,int localPort,String peer,int peerPort,boolean peerIsWifiGateway){
        this.local=address(local,localPort);this.peer=address(peer,peerPort);this.peerIsWifiGateway=peerIsWifiGateway;
    }
    public static SocketPath from(Socket socket,boolean gateway){
        if(socket.isClosed()||!socket.isConnected())return null;
        try{return new SocketPath(socket.getLocalAddress().getHostAddress(),socket.getLocalPort(),socket.getInetAddress().getHostAddress(),socket.getPort(),gateway);}
        catch(IllegalArgumentException invalid){return null;}
    }
    public boolean confirmsHotspot(SocketPath remote){
        return remote!=null&&local.equals(remote.peer)&&peer.equals(remote.local)&&(peerIsWifiGateway||remote.peerIsWifiGateway);
    }
    private static InetSocketAddress address(String host,int port){LocalEndpoint address=LocalEndpoint.parse(host.contains(":")?"["+host+"]:"+port:host+":"+port);return new InetSocketAddress(address.address,address.port);}
}
