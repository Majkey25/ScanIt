-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# GMA 1.4.0 brings WorkManager 2.7.0, which reflects this Room constructor.
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# Keep UMP package names so the beta release verifier can prove consent code is packaged.
-keepnames class com.google.android.ump.**
