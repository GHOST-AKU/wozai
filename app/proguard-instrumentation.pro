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
-keep class dev.ghost.nearbyim.ChatStore { public *; }
-keep class dev.ghost.nearbyim.ChatStore$* { public *; }
-keep class dev.ghost.nearbyim.core.DeviceIdentity { public *; }
-keep class dev.ghost.nearbyim.core.Frame { public *; }
-keep class dev.ghost.nearbyim.core.FramedSession { public *; }
-keep class dev.ghost.nearbyim.core.FramedSession$* { public *; }
-keep class dev.ghost.nearbyim.core.StreamConnection { public *; }
-keep class dev.ghost.nearbyim.i18n.UiText { public *; }
-keep class dev.ghost.nearbyim.i18n.I18nResources { public *; }
-keep class dev.ghost.nearbyim.transport.Peer { public *; }

-keep class dev.ghost.nearbyim.core.Attachment* { public *; }
