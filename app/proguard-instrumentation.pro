# Only the optimized preview uses these hooks. The unsigned production release
# does not need to preserve private fields and methods for UI instrumentation.
-keep class dev.ghost.nearbyim.MainActivity { *; }
-keep class dev.ghost.nearbyim.MainActivity$UiAction { *; }
-keepclassmembers class dev.ghost.nearbyim.ChatService { boolean foreground; }
# Instrumentation is a separate APK. Its public entry points cannot be removed
# or merged into other classes, even when test R8 applies the app's name mapping.
-keep class dev.ghost.nearbyim.AppLanguage { public *; }
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
    void refresh();
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
