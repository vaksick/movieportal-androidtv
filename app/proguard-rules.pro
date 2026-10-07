# Libraries ship their own consumer rules (Jellyfin SDK, kotlinx.serialization, Media3, OkHttp).

# Release builds: drop verbose/debug/info logging calls entirely (warnings and errors stay, credentials are masked
# by RedactingTree). Kotlin calls go through the Timber.Forest companion, Java callers (slf4j-timber) use the statics.
-assumenosideeffects class timber.log.Timber$Forest {
    public void v(...);
    public void d(...);
    public void i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}
