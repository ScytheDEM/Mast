# Release (R8 full mode) keep rules for Signal Atlas.
# Libraries ship their own consumer rules (MapLibre, WorkManager, androidx.sqlite, kotlinx-serialization,
# OkHttp); these cover what is specific to this app.

# WorkManager persists worker class names in its database and the app's factory matches on them:
# keep the update workers' names stable across releases (their 3-argument constructors are not
# covered by WorkManager's own rules).
-keepnames class * extends androidx.work.ListenableWorker

# kotlinx-serialization: generated serializers of the app's @Serializable classes (ImportReport is
# stored as JSON in the DB meta, the basemap cache in DataStore).
-keepclassmembers @kotlinx.serialization.Serializable class au.signalatlas.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class au.signalatlas.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# MapLibre calls back into its Java classes from native code; its consumer rules keep them. Its
# optional integrations are not on the classpath.
-dontwarn org.maplibre.android.**
