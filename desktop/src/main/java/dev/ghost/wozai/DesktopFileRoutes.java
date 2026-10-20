package dev.ghost.wozai;

import dev.ghost.nearbyim.core.AutomaticFileRoutes;
import dev.ghost.nearbyim.core.LocalEndpoint;
import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Ordinary LAN FILE sockets, independent of the user's CHAT listener and mode. */
final class DesktopFileRoutes implements AutomaticFileRoutes.Platform {
    private final ExecutorService worker=new ThreadPoolExecutor(0,2,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),r->daemon(r,"wozai-file-route"),new ThreadPoolExecutor.AbortPolicy());
    private final Set<Socket> pending=ConcurrentHashMap.newKeySet();
    private final Supplier<List<InetAddress>> addresses;
    DesktopFileRoutes(){this(DesktopFileRoutes::ordinaryAddresses);}
    DesktopFileRoutes(Supplier<List<InetAddress>> addresses){this.addresses=Objects.requireNonNull(addresses);}
    public List<AutomaticFileRoutes.Endpoint> currentLanEndpoints(){
        ServerSocket listener=server;
        if(closed)return List.of();
        if(listener==null)return null; // Preparation callback has not established a listener snapshot yet.
        if(listener.isClosed())return List.of();
        List<AutomaticFileRoutes.Endpoint> offered=new ArrayList<>();
        try{for(InetAddress address:new LinkedHashSet<>(addresses.get()))
            if(address!=null&&LocalEndpoint.isLocal(address)&&!address.getHostAddress().contains("%")&&ordinaryLocal(address,address)&&offered.size()<8)
                offered.add(new AutomaticFileRoutes.Endpoint(address.getHostAddress(),listener.getLocalPort()));
        }catch(SocketException|RuntimeException unavailable){return List.of();}
        return closed||server!=listener||listener.isClosed()?List.of():List.copyOf(offered);
    }
    private volatile ServerSocket server;
    private volatile boolean closed;
    private static Thread daemon(Runnable task,String name){Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;}
    public boolean directSupported(){return false;}
    public void openDirect(boolean dialer,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){failed.accept(new IOException("Native desktop Wi-Fi Direct is unsupported"));}
    public void stopDirect(){}
    public void prepareLan(Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints,Consumer<StreamConnection> accepted){
        try{worker.execute(()->{
            ServerSocket listener=null;
            try{
                listener=new ServerSocket(0);
                synchronized(this){if(closed){listener.close();return;}server=listener;}
                endpoints.accept(currentLanEndpoints());
                ServerSocket owned=listener;
                daemon(()->accept(owned,accepted),"wozai-file-accept").start();
            }catch(IOException|RuntimeException unavailable){closeServer(listener);if(!closed)endpoints.accept(List.of());}
        });}catch(RejectedExecutionException unavailable){if(!closed)endpoints.accept(List.of());}
    }
    private void accept(ServerSocket listener,Consumer<StreamConnection> accepted){
        try{while(!closed&&server==listener){
            Socket socket=listener.accept();
            if(!LocalEndpoint.isLocal(socket.getInetAddress())||!ordinaryLocal(socket.getLocalAddress(),socket.getInetAddress())){closeSocket(socket);continue;}
            pending.add(socket);
            try{socket.setTcpNoDelay(true);socket.setKeepAlive(true);if(closed){closeSocket(socket);continue;}accepted.accept(wrap(socket));}
            catch(IOException|RuntimeException failure){closeSocket(socket);}
            finally{pending.remove(socket);}
        }}catch(IOException stopped){/* Listener closure is session-owned. */}
    }
    public void connectLan(List<AutomaticFileRoutes.Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed){
        List<AutomaticFileRoutes.Endpoint> targets=List.copyOf(endpoints);
        try{worker.execute(()->{
            IOException last=new IOException("No ordinary LAN FILE endpoint is available");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            for(AutomaticFileRoutes.Endpoint target:targets){
                if(closed)return;
                Socket socket=new Socket();pending.add(socket);
                try{
                    LocalEndpoint endpoint=LocalEndpoint.parse(DesktopClient.endpoint(InetAddress.getByName(target.host),target.port));
                    InetAddress local=ordinaryLocalFor(endpoint.address);if(local==null)throw new IOException("No proven ordinary LAN interface for FILE endpoint");
                    socket.bind(new InetSocketAddress(local,0));
                    long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<=0){closeSocket(socket);break;}
                    socket.connect(new InetSocketAddress(endpoint.address,endpoint.port),(int)Math.min(remaining,1500));
                    socket.setTcpNoDelay(true);socket.setKeepAlive(true);
                    if(closed){closeSocket(socket);return;}
                    opened.accept(wrap(socket));return;
                }catch(IOException|RuntimeException failure){closeSocket(socket);last=failure instanceof IOException?(IOException)failure:new IOException("LAN FILE connect failed",failure);}
                finally{pending.remove(socket);}
            }
            if(!closed)failed.accept(last);
        });}catch(RejectedExecutionException unavailable){if(!closed)failed.accept(unavailable);}
    }
    private static List<InetAddress> ordinaryAddresses(){
        List<InetAddress> result=new ArrayList<>();
        try{for(NetworkInterface iface:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ordinary(iface))
            for(InetAddress address:Collections.list(iface.getInetAddresses()))if(LocalEndpoint.isLocal(address)&&!address.getHostAddress().contains("%"))result.add(address);
        }catch(SocketException unavailable){return List.of();}
        return List.copyOf(result);
    }
    static boolean ordinaryName(String name){
        String value=name.toLowerCase(Locale.ROOT);
        return !value.startsWith("p2p")&&!value.contains("p2p-")&&!value.startsWith("tun")&&!value.startsWith("utun")&&!value.startsWith("tap")&&!value.startsWith("wg")&&!value.startsWith("tailscale")&&!value.startsWith("zt")&&!value.startsWith("vpn")&&!value.contains("wireguard")&&!value.contains("openvpn")&&!value.contains("wi-fi direct")&&!value.contains("wifi direct")&&!value.contains("wifi-direct")&&!value.contains("wifi_direct");
    }
    static boolean ordinary(NetworkInterface iface)throws SocketException{
        return iface.isUp()&&!iface.isLoopback()&&!iface.isPointToPoint()&&!iface.isVirtual()&&ordinaryName(iface.getName())&&ordinaryName(iface.getDisplayName()==null?iface.getName():iface.getDisplayName());
    }
    static boolean sameNetwork(InetAddress local,short prefix,InetAddress remote){
        byte[] a=local.getAddress(),b=remote.getAddress();
        if(a.length!=b.length||prefix<=0||prefix>a.length*8)return false;
        for(int i=0;i<a.length;i++){int bits=Math.min(8,Math.max(0,prefix-i*8));if(bits==0)break;int mask=0xff<<(8-bits);if((a[i]&mask)!=(b[i]&mask))return false;}
        return true;
    }
    private static InetAddress ordinaryLocalFor(InetAddress remote)throws SocketException{
        InetAddress best=null;short longest=0;
        for(NetworkInterface iface:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ordinary(iface))
            for(InterfaceAddress address:iface.getInterfaceAddresses())if(address.getAddress()!=null&&LocalEndpoint.isLocal(address.getAddress())&&sameNetwork(address.getAddress(),address.getNetworkPrefixLength(),remote)&&address.getNetworkPrefixLength()>longest){best=address.getAddress();longest=address.getNetworkPrefixLength();}
        return best;
    }
    private static boolean ordinaryLocal(InetAddress local,InetAddress remote)throws SocketException{
        NetworkInterface iface=NetworkInterface.getByInetAddress(local);if(iface==null||!ordinary(iface))return false;
        for(InterfaceAddress address:iface.getInterfaceAddresses())if(local.equals(address.getAddress())&&sameNetwork(local,address.getNetworkPrefixLength(),remote))return true;
        return false;
    }
    public synchronized void close(){if(closed)return;closed=true;ServerSocket previous=server;server=null;closeServer(previous);for(Socket socket:pending)closeSocket(socket);pending.clear();worker.shutdownNow();}
    private static StreamConnection wrap(Socket socket){return new StreamConnection(){
        public InputStream input()throws IOException{return socket.getInputStream();}
        public OutputStream output()throws IOException{return socket.getOutputStream();}
        public String label(){return "LAN";}
        public void close()throws IOException{socket.close();}
    };}
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    private static void closeServer(ServerSocket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
}
