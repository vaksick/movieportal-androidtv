enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "movieportal-tv"

// Application
include(":app")

pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google()

		// Jellyfin SDK
		mavenLocal {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "latest-SNAPSHOT")
			}
		}
		maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "master-SNAPSHOT")
				includeVersionByRegex("org.jellyfin.sdk", ".*", "openapi-unstable-SNAPSHOT")
			}
		}
	}
}
