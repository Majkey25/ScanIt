-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# Room 2.2.5 instantiates WorkManager's generated database via Class.newInstance().
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
