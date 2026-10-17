# WorkManager's Room database is created by reflection (WorkDatabase_Impl.<init>()); R8 full mode strips it otherwise
# and the app crashes on launch in androidx.startup.InitializationProvider.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit (OCR) registers its components by reflection; without these constructors text recognition fails in release.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keepnames class * extends java.lang.Throwable

# Glance creates widget button handlers by class name with their no-argument constructor (the widget's › button);
# without this R8 drops the constructor and the tap fails with NoSuchMethodException.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
