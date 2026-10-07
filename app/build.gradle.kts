plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "ua.movieportal.tv"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		// Separate application id so the receiver never replaces the official Jellyfin app
		applicationId = "ua.movieportal.tv"
		versionName = project.getVersionName()
		versionCode = project.getVersionCode()
	}

	buildFeatures {
		buildConfig = true
		resValues = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	signingConfigs {
		// Upload key for Play App Signing; without it the release build is produced unsigned
		project.findReleaseKeystore()?.let { keystore ->
			create("release") {
				storeFile = keystore.storeFile
				storePassword = keystore.storePassword
				keyAlias = keystore.keyAlias
				keyPassword = keystore.keyPassword
			}
		}
	}

	dependenciesInfo {
		includeInBundle = false
		includeInApk = false
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

			resValue("string", "app_name", "Movie Portal TV")

			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			// Debug and release builds can be installed side by side
			applicationIdSuffix = ".debug"
			versionNameSuffix = "-debug"

			resValue("string", "app_name", "Movie Portal TV (debug)")
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

base.archivesName.set("movieportal-tv-v${project.getVersionName()}")

dependencies {
	// Jellyfin
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.lifecycle.runtime)

	// Networking (WebSocket to the Jellyfin server)
	implementation(libs.okhttp)

	// Media player
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.exoplayer.hls)
	implementation(libs.androidx.media3.datasource.okhttp)
	implementation(libs.androidx.media3.ui)
	implementation(libs.jellyfin.androidx.media3.ffmpeg.decoder)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}
