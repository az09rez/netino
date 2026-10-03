# gomobile bindings (libv2ray) are called via JNI
-keep class libv2ray.** { *; }
-keep class go.** { *; }
# WireGuard backend
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.netino.vpn.data.** { *; }
# Strip logs from release builds (privacy)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
