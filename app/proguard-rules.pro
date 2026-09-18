# R8 is on for release, mainly for size: material-icons-extended alone ships thousands of classes
# for the handful this app imports.
#
# Most of what we depend on carries its own consumer rules (Compose, kotlinx.serialization,
# WorkManager, OkHttp, play-services-auth), so this file only covers the things R8 cannot see
# being used.

# WorkManager instantiates workers reflectively by class name. androidx.work ships a rule for its
# own ListenableWorker subclasses, but being explicit here is cheap, and the failure mode is not:
# the worker silently fails to construct and auto-backup stops with nothing said.
-keep class com.backupkit.BackupWorker { <init>(...); }

# kotlinx.serialization generates a companion .serializer() per @Serializable class and reaches it
# by name. Its own rules cover this; keeping our model explicitly means a rules change upstream
# cannot quietly break reading the savings file.
-keepclassmembers class com.tharaa.savings.** {
    *** Companion;
}
-keepclasseswithmembers class com.tharaa.savings.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Crash traces from a release build are unreadable without these.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
