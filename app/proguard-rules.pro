# WorkManager's Room database is created by reflection (WorkDatabase_Impl.<init>()); R8 full mode strips it otherwise
# and the app crashes on launch in androidx.startup.InitializationProvider.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit (OCR) registers its components by reflection; without these constructors text recognition fails in release.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keepnames class * extends java.lang.Throwable
