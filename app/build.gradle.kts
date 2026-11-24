import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nanci.awalocation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nanci.awalocation"
        minSdk = 26
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = "2.0.0" + ".r${getGitCommitCount()}." + getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a"))
        }

//        val googleServicesFile = project.file("google-services.json")
//        if (!googleServicesFile.exists()) {
//            throw GradleException("在 CI 环境中必须提供 google-services.json 文件!")
//        }

        manifestPlaceholders["BUGLY_APPID"] = providers.gradleProperty("BUGLY_APPID")
            .orElse(providers.environmentVariable("BUGLY_APPID"))
            .orElse(resolveLocalProperty(rootProject.file("local.properties"), "BUGLY_APPID"))
            .orElse("")
            .get()
            .trim()
        manifestPlaceholders["AMAP_API_KEY"] = providers.gradleProperty("AMAP_API_KEY")
            .orElse(providers.environmentVariable("AMAP_API_KEY"))
            .orElse(resolveLocalProperty(rootProject.file("local.properties"), "AMAP_API_KEY"))
            .orElse("YOUR_AMAP_KEY")
            .get()
            .trim()
            .ifBlank { "YOUR_AMAP_KEY" }

        val deviceName = providers.environmentVariable("HOSTNAME")
            .orElse(providers.environmentVariable("COMPUTERNAME"))
            .orElse("runner")
            .get()
        val buildPath = project.rootDir.absolutePath.replace("\\", "/")
        manifestPlaceholders["BUGLY_BUILD_ENV"] = "DEVICE:$deviceName,PATH:$buildPath"
        manifestPlaceholders["APP_CHANNEL"] = "release-$deviceName"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["APP_VERSION"] = defaultConfig.versionName ?: "UnknownVersion"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "false"
        }

        debug {
            manifestPlaceholders["APP_VERSION"] = "${defaultConfig.versionName}-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
    }

    flavorDimensions.add("mode")

    productFlavors {
        create("app") {
            dimension = "mode"
            ndk {
                println("Default arm64 build.")
                abiFilters.add("arm64-v8a")
            }
        }
        create("arm64") {
            dimension = "mode"
            ndk {
                println("Full compilation of arm64 architecture")
                abiFilters.add("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/armeabi/**"
            excludes += "lib/armeabi-v7a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/DEPENDENCIES.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/dependencies.txt"
            excludes += "/META-INF/LGPL2.1"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
            excludes += "lib/armeabi/**"
            excludes += "lib/armeabi-v7a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
    }
    configureAppSigningConfigsForRelease(project)
}

// AGP 9 已移除 android.applicationVariants，改用 androidComponents 自定义 APK 文件名。
// flavor: app -> all, arm64 -> arm64，输出形如 awalocation-v<versionName>-<abi>.apk
androidComponents {
    onVariants { variant ->
        val abiName = when (val flavor = variant.flavorName.orEmpty()) {
            "app" -> "all"
            else -> flavor
        }
        val defaultVer = android.defaultConfig.versionName.orEmpty()
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { v -> "awalocation-v$v-$abiName.apk" }
                    .orElse("awalocation-v$defaultVer-$abiName.apk")
            )
        }
    }
}

fun configureAppSigningConfigsForRelease(project: Project) {
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    if (keystorePath.isNullOrBlank()) {
        return
    }
    project.configure<ApplicationExtension> {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storeType = System.getenv("KEYSTORE_TYPE") ?: "jks"
                enableV2Signing = true
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.findByName("release")
            }
            debug {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.okhttp)

    implementation(libs.fastjson)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.reflect)

    implementation(libs.bugly)

    // 高德地图 SDK（3D地图 + 定位 + 检索合包）
    implementation(libs.amap.sdk)

    implementation(libs.geotools)

    testImplementation(libs.junit)
}

fun getGitCommitCount(): Int {
    return runCommand("git", "rev-list", "--count", "HEAD").toIntOrNull() ?: 1
}

fun getGitCommitHash(): String {
    val hash = runCommand("git", "rev-parse", "--short", "HEAD")
    return if (hash.isNotEmpty()) hash else "dev"
}

fun runCommand(vararg command: String): String {
    return try {
        providers.exec {
            commandLine(*command)
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) {
        ""
    }
}

fun getVersionCode(): Int {
    return (System.currentTimeMillis() / 1000L).toInt()
}

fun getVersionName(): String {
    return getGitCommitHash()
}

fun resolveLocalProperty(localPropertiesFile: java.io.File, key: String): String {
    if (!localPropertiesFile.isFile) return ""
    val prefix = "$key="
    return localPropertiesFile.readLines()
        .firstOrNull { it.trimStart().startsWith(prefix) }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()
}

