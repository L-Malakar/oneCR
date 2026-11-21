# oneCR ProGuard/R8 rules.
# Minification is disabled for M1; rules below prepare the ground for when it is
# enabled (M4+) and protect reflective surfaces introduced later.

# JNI bridges (:ai:whisper, :ai:llm, M5) — keep everything reachable from native code
-keepclasseswithmembernames class com.lmalakar.onecr.ai.** {
    native <methods>;
}

# AIDL/shell capture service surface (:privilege:*, M2) — keep parcelables intact
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
