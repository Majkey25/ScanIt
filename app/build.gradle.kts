import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredKeystorePropertiesFile =
    providers.gradleProperty("seliaScanKeystoreProperties").orNull?.let { path ->
        File(path).also { file ->
            require(file.isAbsolute) { "SeliaScan keystore properties path must be absolute" }
            require(file.isFile) { "Configured SeliaScan keystore properties file is missing" }
        }
    }
val externalKeystorePropertiesFile =
    File(System.getProperty("user.home"), ".android/scanit/keystore.properties")
fun requireExternalSigningFile(file: File): File = file.toPath().toRealPath().toFile().also {
    require(!it.toPath().startsWith(rootProject.projectDir.toPath().toRealPath())) {
        "Signing properties and keys must stay outside the project directory"
    }
}

val keystorePropertiesFile =
    (configuredKeystorePropertiesFile ?: externalKeystorePropertiesFile).let {
        if (it.isFile) requireExternalSigningFile(it) else it
    }
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

android {
    namespace = "com.majkeylab.scanit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.majkeylab.scanit"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 59
        versionName = "2.0.1"
        buildConfigField("boolean", "ENABLE_DONATIONS", "false")
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile =
                    requireExternalSigningFile(
                        File(requireNotNull(keystoreProperties.getProperty("storeFile")) {
                            "storeFile is missing from keystore.properties"
                        }).let { file ->
                            if (file.isAbsolute) file else File(keystorePropertiesFile.parentFile, file.path)
                        },
                    )
                storePassword =
                    requireNotNull(keystoreProperties.getProperty("storePassword")) {
                        "storePassword is missing from keystore.properties"
                    }
                keyAlias =
                    requireNotNull(keystoreProperties.getProperty("keyAlias")) {
                        "keyAlias is missing from keystore.properties"
                    }
                keyPassword =
                    requireNotNull(keystoreProperties.getProperty("keyPassword")) {
                        "keyPassword is missing from keystore.properties"
                    }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_DONATIONS", "true")
            applicationIdSuffix = ".github"
        }
        create("internal") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_DONATIONS", "true")
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.enable = variantBuilder.name in setOf(
            "playRelease",
            "githubRelease",
            "internalDebug",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
