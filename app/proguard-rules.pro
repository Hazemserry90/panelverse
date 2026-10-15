# ─────────────────────────────────────────────────────────────────────────────
# FIX 10: R8 / ProGuard rules for the release build (minify + shrink enabled)
# ─────────────────────────────────────────────────────────────────────────────

# Keep generic signatures + annotations (needed by Firebase/Gson-style reflection)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# ── App models ────────────────────────────────────────────────────────────────
# MangaItem is used across the catalogue and copied via data-class equality
-keep class com.example.testing.MangaItem { *; }

# Firebase Realtime Database POJOs deserialised with getValue(Class)
-keep class com.example.testing.SignupFragment$User { *; }
-keep class com.example.testing.LoginFragment$* { *; }
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <fields>;
}
# Generic POJO safety: keep no-arg constructors of app model classes
-keepclassmembers class com.example.testing.** {
    <init>();
}

# ── android-gif-drawable ──────────────────────────────────────────────────────
-keep class pl.droidsonroids.gif.** { *; }
-dontwarn pl.droidsonroids.gif.**

# ── WorkManager workers ───────────────────────────────────────────────────────
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class * extends androidx.work.Worker {
    public <init>(...);
}
