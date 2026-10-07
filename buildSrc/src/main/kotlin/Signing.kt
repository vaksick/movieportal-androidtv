import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * Upload key for Google Play, read from a properties file outside the repository:
 * env MOVIEPORTAL_KEYSTORE_PROPERTIES or ~/.movieportal-tv/keystore.properties with
 * storeFile, storePassword, keyAlias, keyPassword. A relative storeFile is resolved against the properties file.
 */
data class ReleaseKeystore(
	val storeFile: File,
	val storePassword: String,
	val keyAlias: String,
	val keyPassword: String,
)

fun Project.findReleaseKeystore(): ReleaseKeystore? {
	val path = System.getenv("MOVIEPORTAL_KEYSTORE_PROPERTIES")
		?: File(System.getProperty("user.home"), ".movieportal-tv/keystore.properties").path
	val file = File(path)
	if (!file.isFile) {
		logger.info("No release keystore properties at $path, release builds stay unsigned")
		return null
	}

	val properties = Properties().apply { file.inputStream().use(::load) }
	fun value(key: String) = requireNotNull(properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }) {
		"$key is missing in $path"
	}

	val storeFile = File(value("storeFile")).let { if (it.isAbsolute) it else File(file.parentFile, it.path) }
	return ReleaseKeystore(storeFile, value("storePassword"), value("keyAlias"), value("keyPassword"))
}
