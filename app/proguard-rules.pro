# oneCR ProGuard/R8 rules.

# Shizuku user service (M2): the Shizuku server loads ShellService reflectively
# from this APK's dex — the class, its constructor and the AIDL surface must stay
# intact, or the privileged bridge dies at runtime with ClassNotFound.
-keep class com.lmalakar.onecr.privilege.shizuku.ShellService { *; }
-keep class com.lmalakar.onecr.privilege.shizuku.IShellService { *; }
-keep class com.lmalakar.onecr.privilege.shizuku.IShellService$* { *; }

# JNI bridges (:ai:whisper, :ai:llm, M5) — keep everything reachable from native code
-keepclasseswithmembernames class com.lmalakar.onecr.ai.** {
    native <methods>;
}

# AIDL/shell capture service surface (:privilege:*, M2) — keep parcelables intact
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
