import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize") // required for @Parcelize on WipeConfig
}

android {
    namespace = "com.mnmyounus.yfp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mnmyounus.yfp"
        minSdk = 24 // Android 7.0 — StatFs/SAF APIs used here need this floor; also fine for most Android TV boxes in the field.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Signing config is read from environment variables so CI can inject
    // secrets without ever committing a keystore or passwords to the repo.
    // Locally, these env vars are simply unset and Gradle falls back to an
    // unsigned/debug build, which is fine for local testing.
    signingConfigs {
        create("release") {
            val storeFileB64 = System.getenv("YFP_KEYSTORE_BASE64")
            val storePwd = System.getenv("YFP_KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("YFP_KEY_ALIAS")
            val keyPwd = System.getenv("YFP_KEY_PASSWORD")

            if (!storeFileB64.isNullOrBlank() && !storePwd.isNullOrBlank() &&
                !keyAliasEnv.isNullOrBlank() && !keyPwd.isNullOrBlank()
            ) {
                val decodedKeystore = File.createTempFile("yfp_release", ".jks")
                decodedKeystore.writeBytes(Base64.getDecoder().decode(storeFileB64))
                decodedKeystore.deleteOnExit()

                storeFile = decodedKeystore
                storePassword = storePwd
                keyAlias = keyAliasEnv
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing config if it was actually
            // populated above (i.e. secrets were present). Otherwise leave
            // the build unsigned so local `assembleRelease` doesn't hard-fail.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.leanback:leanback:1.0.0") // Android TV D-pad-friendly widgets

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
