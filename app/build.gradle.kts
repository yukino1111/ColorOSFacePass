import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.yukino1111.colorosfacepass"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.yukino1111.colorosfacepass"
        minSdk = 29
        targetSdk = 36

        versionCode = 1
        versionName = "1.0.0"
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters.add("en")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties =
        Properties().apply {
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
        }

    signingConfigs {
        create("release") {
            fun secret(name: String): String? =
                keystoreProperties.getProperty(name)
                    ?: providers
                        .gradleProperty(name)
                        .orElse(providers.environmentVariable(name))
                        .orNull

            val storeFilePath = secret("storeFile") ?: secret("RELEASE_STORE_FILE")
            val storePassword = secret("storePassword") ?: secret("RELEASE_STORE_PASSWORD")
            val keyAlias = secret("keyAlias") ?: secret("RELEASE_KEY_ALIAS")
            val keyPassword = secret("keyPassword") ?: secret("RELEASE_KEY_PASSWORD") ?: storePassword
            val storeType = secret("storeType") ?: secret("RELEASE_STORE_TYPE")

            val releaseStoreFile =
                storeFilePath?.let {
                    rootProject.file(it).takeIf { f -> f.exists() }
                        ?: file(it).takeIf { f -> f.exists() }
                }

            if (releaseStoreFile != null && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank()) {
                storeFile = releaseStoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                if (!storeType.isNullOrBlank()) {
                    this.storeType = storeType
                }

                enableV1Signing = true
                enableV2Signing = true
            } else if (keystorePropertiesFile.exists()) {
                println("Release keystore not found, assembleRelease will build an unsigned release artifact.")
            } else {
                logger.warn("Neither keystore.properties nor RELEASE_STORE_FILE found. Release signing is disabled.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null && it.storeFile!!.exists() }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes +=
                setOf(
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/NOTICE",
                    "META-INF/NOTICE.txt",
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/*.kotlin_module",
                    "META-INF/INDEX.LIST",
                    "kotlin-tooling-metadata.json",
                    "kotlin/**",
                    "META-INF/services/*",
                    "META-INF/com/android/build/gradle/*",
                    "META-INF/version-control-info.textproto",
                )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable.addAll(listOf("OldTargetApi", "PrivateApi", "DiscouragedPrivateApi"))
        ignoreTestSources = true
    }
}

kotlin { jvmToolchain(21) }

ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(false)
}

// AGP 9 built-in Kotlin doesn't register source sets that the ktlint plugin can discover.
// Run ktlint 1.8.0 directly on source files as a workaround.
val ktlintSrc by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs ktlint 1.8.0 on Kotlin source files"
    mainClass.set("com.pinterest.ktlint.Main")
    classpath =
        configurations.detachedConfiguration(
            dependencies.create("com.pinterest.ktlint:ktlint-cli:1.8.0"),
        )
    args("src/**/*.kt")
}

val ktlintFormatSrc by tasks.registering(JavaExec::class) {
    group = "formatting"
    description = "Fixes ktlint formatting on Kotlin source files"
    mainClass.set("com.pinterest.ktlint.Main")
    classpath =
        configurations.detachedConfiguration(
            dependencies.create("com.pinterest.ktlint:ktlint-cli:1.8.0"),
        )
    args("-F", "src/**/*.kt")
}

tasks.named("check").configure {
    dependsOn(ktlintSrc)
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation("io.github.libxposed:service:101.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
