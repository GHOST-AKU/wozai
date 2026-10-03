# Only the optimized preview uses these hooks. The unsigned production release
# does not need to preserve private fields and methods for UI instrumentation.
-keep class dev.ghost.nearbyim.MainActivity { *; }
-keep class dev.ghost.nearbyim.MainActivity$UiAction { *; }
-keepclassmembers class dev.ghost.nearbyim.ChatService { boolean foreground; }
