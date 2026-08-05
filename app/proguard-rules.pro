# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Domain models used in Room / Gson / Intent extras
-keep class com.runner.academy.data.** { *; }

# Gson / checkpoint models outside data.* — R8 was renaming fields
# (dateMillis→b, kind→b), breaking backup JSON and checkpoint restore.
-keep class com.runner.academy.util.WorkoutBackupFormat$* { *; }
-keep class com.runner.academy.util.TrainingPlanBackupFormat$* { *; }
-keep class com.runner.academy.service.ActiveWorkoutCheckpoint { *; }
-keep class com.runner.academy.service.IntervalCursor { *; }

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Google Play Services Location
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Safe Args — keep Args classes and Companion.fromBundle by name (reflection)
-keepnames class * implements androidx.navigation.NavArgs
-keep class * implements androidx.navigation.NavArgs { *; }
-keep class **.*Args$Companion { *; }
-keepclassmembers class **.*Args$Companion {
    public *** fromBundle(android.os.Bundle);
    public *** fromSavedStateHandle(androidx.lifecycle.SavedStateHandle);
}
-keepclassmembers class * implements androidx.navigation.NavArgs {
    public static *** fromBundle(android.os.Bundle);
    public static *** fromSavedStateHandle(androidx.lifecycle.SavedStateHandle);
}
