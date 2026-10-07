import org.gradle.api.Project

/**
 * Version name from the "app.versionName" Gradle property (gradle.properties, override with -Papp.versionName=…). A leading "v" (e.g. from a git tag) is removed.
 */
fun Project.getVersionName(): String =
	requireNotNull(getProperty("app.versionName")) { "app.versionName is not set" }.removePrefix("v")

/**
 * Version code from the "app.versionCode" Gradle property (gradle.properties, override with -Papp.versionCode=…).
 * Must be increased for every upload to Google Play.
 */
fun Project.getVersionCode(): Int =
	requireNotNull(getProperty("app.versionCode")?.toIntOrNull()) { "app.versionCode must be an integer" }
