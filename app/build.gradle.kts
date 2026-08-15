import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

android {
    namespace = "com.majkeylab.scanit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.majkeylab.scanit"
        minSdk = 33
        targetSdk = 36
        versionCode = 13
        versionName = "1.2.5"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile =
                    rootProject.file(
                        requireNotNull(keystoreProperties.getProperty("storeFile")) {
                            "storeFile is missing from keystore.properties"
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
            applicationIdSuffix = ".github"
        }
        create("internal") {
            dimension = "distribution"
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
}
