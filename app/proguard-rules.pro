# oneCR ProGuard/R8 rules.

# Shizuku user service (M2): the Shizuku server loads ShellService reflectively
# from this APK's dex — the class, its constructor and the AIDL surface must stay
# intact, or the privileged bridge dies at runtime with ClassNotFound.
-keep class com.lmalakar.onecr.privilege.shizuku.ShellService { *; }
-keep class com.lmalakar.onecr.privilege.shizuku.IShellService { *; }
-keep class com.lmalakar.onecr.privilege.shizuku.IShellService$* { *; }

# Shell capture process (M3, v0.3.6): CaptureMain is spawned via app_process
# with this APK on the CLASSPATH and reached ONLY by reflection from the
# native launcher (`app_process / com.lmalakar.onecr.…CaptureMain …`) — R8
# would strip it without an explicit keep and every capture would die with
# ClassNotFound.
-keep class com.lmalakar.onecr.service.recorder.capture.** { *; }

# Bundled Conscrypt (embedded ADB bridge, M2.5): the TLS stack is a JNI +
# reflection-heavy library; keep it whole so the pairing/connect handshakes
# and SSL_export_keying_material survive shrinking.
-keep class org.conscrypt.** { *; }
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.**

# JNI bridges (:ai:whisper, :ai:llm, M5) — keep everything reachable from native code
-keepclasseswithmembernames class com.lmalakar.onecr.ai.** {
    native <methods>;
}

# AIDL/shell capture service surface (:privilege:*, M2) — keep parcelables intact
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
