package org.nearbymesh.probe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.*;
import android.net.wifi.p2p.*;
import android.os.*;
import org.json.*;

import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Internal bounded observation tool. Callback success never implies a working mesh data path. */
public final class CapabilityProbe extends Instrumentation {
    private static final UUID SERVICE = UUID.fromString("930fc66b-b622-42e4-9f0c-46e7b94fe619");
    private final String session = UUID.randomUUID().toString();
    private final long started = SystemClock.elapsedRealtime();
    private volatile boolean finished;
    private int eventCount;
    private Bundle args;
    private Context context;
    private Handler handler;
    private HandlerThread thread;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private String ownedGroupName;
    private volatile boolean stopping;
    private boolean receiverRegistered;
    private WifiAwareSession awareSession;
    private DiscoverySession publisher;
    private DiscoverySession subscriber;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            event("wifi.broadcast", "action", intent.getAction());
            snapshotP2p();
        }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        context = getTargetContext();
        String mode = args.getString("mode", "inventory");
        if (!Arrays.asList("inventory", "wifi-discover", "wifi-group", "aware", "ble").contains(mode)) {
            event("run.invalid_mode");
            Bundle end = new Bundle(); end.putString("stream", "Unknown mode; no radio operations performed.\n");
            finish(Activity.RESULT_CANCELED, end); return;
        }
        int seconds = 8;
        try { seconds = Integer.parseInt(args.getString("seconds", "8")); }
        catch (NumberFormatException ignored) { event("run.invalid_duration", "using_default", 8); }
        seconds = Math.max(2, Math.min(seconds, 30));
        event("run.start", "mode", mode, "seconds", seconds,
            "sdk", Build.VERSION.SDK_INT, "target_sdk", context.getApplicationInfo().targetSdkVersion,
            "manufacturer", Build.MANUFACTURER, "model", Build.MODEL,
            "data_path_tested", false, "background_tested", false);
        thread = new HandlerThread("mesh-capability-probe"); thread.start();
        handler = new Handler(thread.getLooper());
        final String selectedMode = mode;
        handler.post(() -> {
            attempt("inventory", this::inventory);
            attempt("wifi.initialize", this::initializeP2p);
            if ("wifi-discover".equals(selectedMode)) attempt("wifi.discover", () -> {
                if (p2p != null && channel != null) p2p.discoverPeers(channel, action("wifi.discover.request", null));
            });
            if ("wifi-group".equals(selectedMode)) attempt("wifi.create_group", this::createGroup);
            if ("aware".equals(selectedMode)) attempt("aware.attach", this::startAware);
            if ("ble".equals(selectedMode)) attempt("ble.start", this::startBle);
        });
        try { Thread.sleep(seconds * 1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); event("run.interrupted"); }
        stopping = true;
        CountDownLatch cleaned = new CountDownLatch(1);
        handler.post(() -> cleanup(cleaned));
        try { if (!cleaned.await(3, TimeUnit.SECONDS)) event("cleanup.timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        event("run.end", "data_path_tested", false, "mesh_validation", "pending_hardware_data_path");
        finished = true;
        thread.quitSafely();
        Bundle end = new Bundle(); end.putString("stream", "Observation ended. No mesh or throughput claim.\n");
        finish(Activity.RESULT_OK, end);
    }

    private void inventory() throws Exception {
        PackageManager pm = context.getPackageManager();
        event("features", "wifi_direct", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            "wifi_aware", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            "bluetooth", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            "bluetooth_le", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        for (String permission : new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}) {
            event("permission", "name", permission,
                "granted", context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        }
        LocationManager location = context.getSystemService(LocationManager.class);
        event("location", "enabled", location != null && location.isLocationEnabled());
        attempt("wifi.flags", () -> {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi == null) { event("wifi.manager_absent"); return; }
            event("wifi.state", "enabled", wifi.isWifiEnabled());
            if (Build.VERSION.SDK_INT >= 30)
                event("wifi.sta_ap", "reported_supported", wifi.isStaApConcurrencySupported());
            if (Build.VERSION.SDK_INT >= 31)
                event("wifi.sta_local_only", "reported_supported", wifi.isStaConcurrencyForLocalOnlyConnectionsSupported());
        });
        attempt("aware.flags", () -> {
            WifiAwareManager aware = context.getSystemService(WifiAwareManager.class);
            event("aware.state", "manager_present", aware != null,
                "available", aware != null && aware.isAvailable());
            if (aware != null && Build.VERSION.SDK_INT >= 31 && aware.isAvailable()) {
                AwareResources resources = aware.getAvailableAwareResources();
                if (resources != null) event("aware.resources",
                    "data_paths", resources.getAvailableDataPathsCount(),
                    "publish_sessions", resources.getAvailablePublishSessionsCount(),
                    "subscribe_sessions", resources.getAvailableSubscribeSessionsCount());
            }
        });
        attempt("bluetooth.flags", () -> {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) { event("bluetooth.adapter_absent"); return; }
            event("bluetooth.state", "enabled", adapter.isEnabled(),
                "advertiser_present", adapter.getBluetoothLeAdvertiser() != null,
                "scanner_present", adapter.getBluetoothLeScanner() != null,
                "multiple_advertisements", adapter.isMultipleAdvertisementSupported());
        });
        interfaces();
    }

    private void interfaces() throws Exception {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null) return;
        while (all.hasMoreElements()) {
            NetworkInterface iface = all.nextElement();
            int ipv4 = 0, ipv6 = 0, linkLocal6 = 0;
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress a = addresses.nextElement();
                if (a instanceof Inet4Address) ipv4++;
                if (a instanceof Inet6Address) { ipv6++; if (a.isLinkLocalAddress()) linkLocal6++; }
            }
            event("interface", "name", iface.getName(), "up", iface.isUp(),
                "ipv4_count", ipv4, "ipv6_count", ipv6, "link_local_ipv6_count", linkLocal6);
        }
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) continue;
            event("network", "token", token(n.toString()),
                "interface", lp == null ? JSONObject.NULL : lp.getInterfaceName(),
                "wifi", nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                "aware", nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE),
                "cellular", nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                "internet_capability", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }
    }

    private void initializeP2p() {
        p2p = context.getSystemService(WifiP2pManager.class);
        if (p2p == null) { event("wifi.p2p_manager_absent"); return; }
        channel = p2p.initialize(context, handler.getLooper(), () -> event("wifi.channel_disconnected"));
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        context.registerReceiver(receiver, filter, null, handler);
        receiverRegistered = true;
        snapshotP2p();
    }

    private void snapshotP2p() {
        if (p2p == null || channel == null || finished) return;
        attempt("wifi.group_info", () -> p2p.requestGroupInfo(channel, group -> {
            if (group == null) { event("wifi.group", "present", false); return; }
            event("wifi.group", "present", true, "owner", group.isGroupOwner(),
                "interface", group.getInterface(), "clients", group.getClientList().size(),
                "ssid_token", token(group.getNetworkName()));
        }));
        attempt("wifi.connection_info", () -> p2p.requestConnectionInfo(channel, info ->
            event("wifi.connection", "group_formed", info.groupFormed,
                "owner", info.isGroupOwner,
                "owner_address_token", info.groupOwnerAddress == null ? JSONObject.NULL : token(info.groupOwnerAddress.getHostAddress()))));
        attempt("wifi.peers", () -> p2p.requestPeers(channel, peers -> {
            event("wifi.peer_count", "count", peers.getDeviceList().size());
            for (WifiP2pDevice device : peers.getDeviceList())
                event("wifi.peer", "token", token(device.deviceAddress), "status", device.status);
        }));
    }

    private void createGroup() {
        if (p2p == null || channel == null) return;
        p2p.requestGroupInfo(channel, group -> {
            if (stopping) { event("wifi.create_group.skipped_stopping"); return; }
            if (group != null) { event("wifi.create_group.skipped_existing_group"); return; }
            attempt("wifi.create_group", () -> {
                ownedGroupName = "DIRECT-NM-MeshProbe-" + session.substring(0, 8);
                WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName(ownedGroupName)
                    .setPassphrase(UUID.randomUUID().toString().substring(0, 16))
                    .build();
                p2p.createGroup(channel, config, action("wifi.create_group.request", this::snapshotP2p));
            });
        });
    }

    private WifiP2pManager.ActionListener action(String name, Runnable accepted) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                event(name, "accepted", true, "data_path_proven", false);
                if (accepted != null) accepted.run();
            }
            @Override public void onFailure(int reason) {
                event(name, "accepted", false, "reason_code", reason);
            }
        };
    }

    private void startAware() {
        WifiAwareManager manager = context.getSystemService(WifiAwareManager.class);
        if (manager == null || !manager.isAvailable()) { event("aware.unavailable"); return; }
        manager.attach(new AttachCallback() {
            @Override public void onAttachFailed() { event("aware.attach_failed"); }
            @Override public void onAttached(WifiAwareSession value) {
                if (stopping) { value.close(); return; }
                awareSession = value; event("aware.attached");
                attempt("aware.publish", () -> value.publish(new PublishConfig.Builder()
                    .setServiceName("nearby-mesh-capability-v1").build(), new DiscoverySessionCallback() {
                        @Override public void onPublishStarted(PublishDiscoverySession s) {
                            if (stopping) { s.close(); return; }
                            publisher = s; event("aware.publish_started");
                        }
                        @Override public void onSessionConfigFailed() { event("aware.publish_failed"); }
                    }, handler));
                attempt("aware.subscribe", () -> value.subscribe(new SubscribeConfig.Builder()
                    .setServiceName("nearby-mesh-capability-v1").build(), new DiscoverySessionCallback() {
                        @Override public void onSubscribeStarted(SubscribeDiscoverySession s) {
                            if (stopping) { s.close(); return; }
                            subscriber = s; event("aware.subscribe_started");
                        }
                        @Override public void onSessionConfigFailed() { event("aware.subscribe_failed"); }
                        @Override public void onServiceDiscovered(PeerHandle peer, byte[] data, List<byte[]> filter) {
                            event("aware.service_discovered", "peer_token", token(Integer.toString(peer.hashCode())));
                        }
                    }, handler));
            }
        }, handler);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanFailed(int errorCode) { event("ble.scan_failed", "reason_code", errorCode); }
        @Override public void onScanResult(int type, ScanResult result) {
            attempt("ble.scan_result", () -> event("ble.probe_peer", "peer_token", token(result.getDevice().getAddress()),
                "rssi", result.getRssi()));
        }
    };
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            event("ble.advertise_started", "connectable", settings.isConnectable());
        }
        @Override public void onStartFailure(int code) { event("ble.advertise_failed", "reason_code", code); }
    };

    private void startBle() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { event("ble.unavailable"); return; }
        attempt("ble.gatt_server", () -> {
            gattServer = manager.openGattServer(context, new BluetoothGattServerCallback() {
                @Override public void onServiceAdded(int status, BluetoothGattService service) {
                    event("ble.gatt_service_added", "status", status);
                }
                @Override public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
                    event("ble.gatt_connection", "status", status, "state", state);
                }
            });
            if (gattServer == null) { event("ble.gatt_server_absent"); return; }
            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            event("ble.gatt_service_request", "accepted", gattServer.addService(service));
        });
        attempt("ble.scan", () -> {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { event("ble.scanner_absent"); return; }
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback);
            event("ble.scan_requested");
        });
        attempt("ble.advertise", () -> {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { event("ble.advertiser_absent"); return; }
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setConnectable(true).build(),
                new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build(), advertiseCallback);
        });
    }

    private void cleanup(CountDownLatch done) {
        if (scanner != null) attempt("cleanup.ble_scan", () -> scanner.stopScan(scanCallback));
        if (advertiser != null) attempt("cleanup.ble_advertise", () -> advertiser.stopAdvertising(advertiseCallback));
        if (gattServer != null) attempt("cleanup.gatt", () -> gattServer.close());
        if (publisher != null) attempt("cleanup.aware_publish", () -> publisher.close());
        if (subscriber != null) attempt("cleanup.aware_subscribe", () -> subscriber.close());
        if (awareSession != null) attempt("cleanup.aware_session", () -> awareSession.close());
        attempt("interfaces.final", this::interfaces);
        if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false; }
        if (p2p != null && channel != null) {
            if ("wifi-discover".equals(args.getString("mode")))
                attempt("cleanup.wifi_discovery", () -> p2p.stopPeerDiscovery(channel, action("wifi.discovery_stop", null)));
            if (ownedGroupName != null) {
                attempt("cleanup.wifi_group", () -> p2p.requestGroupInfo(channel, group -> {
                    if (group == null || !group.isGroupOwner() || !ownedGroupName.equals(group.getNetworkName())) {
                        event("wifi.group_cleanup_skipped_foreign_or_absent"); channel.close(); done.countDown(); return;
                    }
                    attempt("cleanup.wifi_owned_group", () -> p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() { event("wifi.group_removed"); channel.close(); done.countDown(); }
                        @Override public void onFailure(int reason) {
                            event("wifi.group_remove_failed", "reason_code", reason); channel.close(); done.countDown();
                        }
                    }));
                }));
                handler.postDelayed(() -> { channel.close(); done.countDown(); }, 1800);
                return;
            }
            channel.close();
        }
        done.countDown();
    }

    private interface Operation { void run() throws Exception; }
    private void attempt(String operation, Operation work) {
        try { work.run(); }
        catch (Exception e) { event("operation.error", "operation", operation, "exception", e.getClass().getSimpleName()); }
    }
    private String token(String value) {
        if (value == null) return "absent";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((session + "|" + value).getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (Exception e) { return "redacted"; }
    }
    private synchronized void event(String name, Object... fields) {
        if (finished) return;
        if (++eventCount > 512 && !name.startsWith("cleanup.") && !name.equals("run.end")) return;
        try {
            JSONObject object = new JSONObject();
            object.put("schema", 1); object.put("session", session); object.put("event", name);
            object.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            for (int i = 0; i < fields.length; i += 2) object.put(fields[i].toString(), fields[i + 1]);
            Bundle status = new Bundle(); status.putString("mesh_event", object.toString()); sendStatus(0, status);
        } catch (JSONException ignored) { /* Never fall back to an unredacted diagnostic dump. */ }
    }
}
