# Only the optimized preview uses these hooks. The unsigned production release
# does not need to preserve private fields and methods for UI instrumentation.
-keep class dev.ghost.nearbyim.MainActivity { *; }
-keep class dev.ghost.nearbyim.MainActivity$UiAction { *; }
-keepclassmembers class dev.ghost.nearbyim.ChatService { boolean foreground; }
# Instrumentation is a separate APK. Its public entry points cannot be removed
# or merged into other classes, even when test R8 applies the app's name mapping.
-keep class dev.ghost.nearbyim.AppLanguage { public *; }
-keep class dev.ghost.nearbyim.AppAppearance { public *; }
-keep class dev.ghost.nearbyim.AndroidText { public *; }
-keep class dev.ghost.nearbyim.ChatController { public *; }
# The separate test APK supplies its own Hooks implementations. Keep this API open
# so R8 cannot replace its interface dispatch with the app's sole concrete callback.
-keep interface dev.ghost.nearbyim.ChatController$Hooks { *; }
-keepclassmembers class dev.ghost.nearbyim.ChatController { java.util.concurrent.ThreadPoolExecutor fileSelection; }
# Native feedback tests inspect the transient map without adding production hooks.
-keepclassmembers class dev.ghost.nearbyim.ChatController { java.util.concurrent.ConcurrentHashMap transferProgress; }
-keepclassmembers class dev.ghost.nearbyim.ChatController {
    java.util.concurrent.ExecutorService storage;
    dev.ghost.nearbyim.ChatStore store;
    java.lang.Runnable observer;
    dev.ghost.nearbyim.core.FramedSession active;
    dev.ghost.nearbyim.core.ClientAttachmentTransfers transfers;
    java.util.concurrent.CompletableFuture attachmentsPreparing;
    void refresh();
    java.util.List liveMessages(java.util.List,java.lang.String);
    boolean beginConnect(int,java.lang.String,boolean,java.lang.String);
    java.lang.String lanReconnectId;
}
-keep class dev.ghost.nearbyim.ChatController$Feedback { *; }
-keep class dev.ghost.nearbyim.ChatStore { public *; }
-keep class dev.ghost.nearbyim.ChatStore$* { public *; }
-keepclassmembers class dev.ghost.nearbyim.ChatStore$Message { <init>(...); }
-keep class dev.ghost.nearbyim.core.DeviceIdentity { public *; }
-keep class dev.ghost.nearbyim.core.IdentityVerification { public *; }
-keep class dev.ghost.nearbyim.core.SafetyCode { public *; }
-keep class dev.ghost.nearbyim.verification.QrCodes { public *; }
# Security fixtures deliberately alter claims before root-proof verification.
-keep class dev.ghost.nearbyim.noise.NoiseRecordChannel { *; }
-keep class dev.ghost.nearbyim.noise.JcaAesGcmCipherState { *; }
-keep class com.southernstorm.noise.protocol.** { public *; }
-keep class dev.ghost.nearbyim.core.Frame { public *; }
-keep class dev.ghost.nearbyim.core.FramedSession { public *; }
-keep class dev.ghost.nearbyim.core.FramedSession$* { public *; }
-keep class dev.ghost.nearbyim.core.StreamConnection { public *; }
-keep class dev.ghost.nearbyim.core.SocketPath { public *; }
# The separate native test APK supplies FILE providers/listeners. Preserve their
# dispatch and constructor contract; app-only R8 must not specialize them to its
# one Android implementation. These rules do not apply to production release.
-keep class dev.ghost.nearbyim.core.AutomaticFileRoutes { public *; }
-keep interface dev.ghost.nearbyim.core.AutomaticFileRoutes$Platform { *; }
-keep interface dev.ghost.nearbyim.core.AutomaticFileRoutes$Listener { *; }
-keep class dev.ghost.nearbyim.core.AutomaticFileRoutes$Endpoint { public *; }
-keep class dev.ghost.nearbyim.core.AutomaticFileRoutes$Opened { public *; }
-keep enum dev.ghost.nearbyim.core.AutomaticFileRoutes$Route { public *; }
-keep class dev.ghost.nearbyim.transport.AndroidFilePlatform { public *; }
-keep class dev.ghost.nearbyim.i18n.UiText { public *; }
-keep class dev.ghost.nearbyim.i18n.I18nResources { public *; }
-keep class dev.ghost.nearbyim.transport.Peer { public *; }
# Controlled route/order fixtures call these package entry points from the test APK.
-keep class dev.ghost.nearbyim.transport.LanTransport {
    static int networkScore(...);
    static int routeScore(...);
    static void bindAndConnect(...);
}
-keep interface dev.ghost.nearbyim.transport.LanTransport$SocketBinding { *; }

-keep class dev.ghost.nearbyim.core.Attachment* { public *; }
-keep class dev.ghost.nearbyim.core.ClientAttachmentTransfers { public *; }
-keep class dev.ghost.nearbyim.AndroidAttachmentSource { public *; }
-keep class dev.ghost.nearbyim.AndroidNoiseIdentity { *; }
-keep class dev.ghost.nearbyim.core.FileAttachmentSource { public *; }
-keep class dev.ghost.nearbyim.core.TransferLimits { public *; }
-keep class dev.ghost.nearbyim.core.Transfer* { public *; }
-keep class dev.ghost.nearbyim.core.Transfer*$* { public *; }
-keep class dev.ghost.nearbyim.PhotoDecoder { public *; }
-keep class dev.ghost.nearbyim.core.ImageOrientation { public *; }
-keep class dev.ghost.nearbyim.PhotoActivity { *; }
-keep class dev.ghost.nearbyim.PhotoActivity$* { *; }

# Native selection and publication contracts are called from the test APK.
-keep class dev.ghost.nearbyim.AttachmentSelection { *; }
-keep class dev.ghost.nearbyim.GalleryPublisher { *; }
-keep class dev.ghost.nearbyim.GalleryPublisher$* { *; }
-keep class dev.ghost.nearbyim.core.PhotoExportStore { *; }
-keep class dev.ghost.nearbyim.core.PhotoExportStore$* { *; }

-keep class dev.ghost.nearbyim.GalleryActivity { *; }
-keep class dev.ghost.nearbyim.GalleryActivity$* { *; }
-keep class dev.ghost.nearbyim.AttachmentSelectionActivity { *; }
-keep class dev.ghost.nearbyim.AttachmentSelectionActivity$* { *; }

# Native avatar checks inspect editor cancellation, exact preview bytes and cache bounds.
-keep class dev.ghost.nearbyim.AvatarActivity { *; }
-keep class dev.ghost.nearbyim.AvatarCropView { *; }
-keep class dev.ghost.nearbyim.AvatarView { *; }
-keep class dev.ghost.nearbyim.AndroidAvatars { *; }
-keep class dev.ghost.nearbyim.AndroidAvatars$* { *; }
-keep class dev.ghost.nearbyim.AvatarImages { public *; }
-keep class dev.ghost.nearbyim.core.Avatar* { public *; }
-keep class dev.ghost.nearbyim.AttachmentLocations { *; }
-keep class dev.ghost.nearbyim.AttachmentLocations$* { *; }
-keep class dev.ghost.nearbyim.core.AttachmentExportStore { public *; }
-keep class dev.ghost.nearbyim.core.AttachmentExportStore$* { public *; }
-keep class dev.ghost.nearbyim.core.TransferPath { public *; }
-keep class dev.ghost.nearbyim.core.Identicon { public *; }
-keep class dev.ghost.nearbyim.core.Identicon$* { public *; }

# Native FILE discovery checks inspect the actual query and asynchronous attempt callbacks.
-keepclassmembers class dev.ghost.nearbyim.transport.AndroidFilePlatform {
    *** direct;
    *** directFailed;
    *** directOpened;
    *** discoveryTimeout;
    *** discoveryBudget;
}
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$DiscoveryDeadline { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectTransport {
    static android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest discoveryRequest();
    static android.net.wifi.p2p.WifiP2pConfig groupConfig(dev.ghost.nearbyim.core.DirectGroupCredentials, boolean);
    dev.ghost.nearbyim.transport.TransportListener listener;
}
-keep class dev.ghost.nearbyim.core.DirectGroupCredentials { public *; }
# Native API34 group snapshots exercise the real handler and queued-cancel lifecycle.
-keepclassmembers class dev.ghost.nearbyim.transport.WifiDirectTransport {
    dev.ghost.nearbyim.transport.WifiDirectPolicy$Lifecycle lifecycle;
    dev.ghost.nearbyim.transport.WifiDirectTransport$CoordinatedGroup coordinated;
    long run;
    long attempt;
    void handleCoordinatedGroup(long,android.net.wifi.p2p.WifiP2pInfo,android.net.wifi.p2p.WifiP2pGroup);
}
-keep class dev.ghost.nearbyim.transport.WifiDirectTransport$CoordinatedGroup { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$KnownGroup { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupReadiness { *; }
-keep enum dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupReadiness$State { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$Lifecycle { *; }
# Preview-only native UID scope and retirement callback fixtures.
-keepclassmembers class dev.ghost.nearbyim.transport.WifiDirectTransport {
    static dev.ghost.nearbyim.transport.WifiDirectPolicy$VpnScope appVpnScope(android.net.NetworkCapabilities);
    dev.ghost.nearbyim.transport.WifiDirectPolicy$SessionAuthority authority;
    dev.ghost.nearbyim.transport.WifiDirectPolicy$CleanupGate cleanup;
    dev.ghost.nearbyim.transport.WifiDirectTransport$KnownGroupClaim retainedGroup;
    dev.ghost.nearbyim.transport.WifiDirectTransport$CleanupWork cleanupWork;
    android.os.Handler main;
    java.lang.Runnable afterCleanup;
    void coordinatedReply(dev.ghost.nearbyim.transport.WifiDirectPolicy$NativeCommand,dev.ghost.nearbyim.transport.WifiDirectTransport$CoordinatedGroup,boolean,int);
    void completeRetirement(dev.ghost.nearbyim.transport.WifiDirectPolicy$NativeCommand);
    void finishRetirement(dev.ghost.nearbyim.transport.WifiDirectTransport$CleanupWork,boolean);
    dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupRetirement$State retirementState(dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupRetirement,android.net.wifi.p2p.WifiP2pGroup);
}
-keep class dev.ghost.nearbyim.transport.WifiDirectTransport$KnownGroupClaim { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectTransport$CleanupWork { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$SessionAuthority { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$NativeCommand { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$CleanupGate { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupRetirement { *; }
-keep enum dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupRetirement$State { *; }
-keep enum dev.ghost.nearbyim.transport.WifiDirectPolicy$VpnScope { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$InterfaceRoute { *; }
-keep class dev.ghost.nearbyim.transport.WifiDirectPolicy$Route { *; }
-keepclassmembers class dev.ghost.nearbyim.transport.WifiDirectPolicy {
    static dev.ghost.nearbyim.transport.WifiDirectPolicy$Route prove(java.lang.String,java.net.InetAddress,boolean,java.util.List,boolean,dev.ghost.nearbyim.transport.WifiDirectPolicy$VpnScope);
}

# Targeted adaptive-window suite measures existing Views and preserves its synthetic hinge API.
-keep class dev.ghost.nearbyim.AdaptiveScaffold { *; }
-keep class dev.ghost.nearbyim.AdaptiveReconnectActions { *; }
-keep class dev.ghost.nearbyim.core.AdaptiveLayout { public *; }
-keep class dev.ghost.nearbyim.core.AdaptiveLayout$* { public *; }
-keepclassmembers class dev.ghost.nearbyim.ChatController {
    dev.ghost.nearbyim.core.AutomaticFileRoutes fileRoutes;
}
-keep class dev.ghost.nearbyim.MainActivity$TimelinePosition { *; }

# SAF receive-directory jobs are inspected by the separate native fixture.
-keep class dev.ghost.nearbyim.core.AttachmentDirectoryStore { *; }
-keep class dev.ghost.nearbyim.core.AttachmentDirectoryStore$* { *; }
