plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lenshift.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lenshift.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release signing reads from environment variables so no keystore password is
        // ever committed. When they are absent (CI without secrets, or a fresh clone) the
        // release build simply falls back to unsigned rather than failing the whole build.
        create("release") {
            val storePath = System.getenv("LENSHIFT_KEYSTORE")
            val storePassword = System.getenv("LENSHIFT_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("LENSHIFT_KEY_ALIAS")
            val keyPassword = System.getenv("LENSHIFT_KEY_PASSWORD")
            if (storePath != null && storePassword != null &&
                keyAlias != null && keyPassword != null
            ) {
                storeFile = file(storePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Side-loading a debug build must replace the installed release build rather
            // than appear as a second app. That requires the same applicationId (no
            // ".debug" suffix) and the same signing identity; otherwise the platform
            // rejects the update and keeps two separate installs on the device.
            if (System.getenv("LENSHIFT_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach signing when the environment actually provided a keystore.
            if (System.getenv("LENSHIFT_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
        // Tesseract and AndroidMath both bundle the NDK C++ runtime.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }

    androidResources {
        // Keep only the locales we actually translate; Tesseract ships none.
        generateLocaleConfig = false
        // AndroidMath ships three math fonts; answers only use Latin Modern.
        ignoreAssetsPatterns += listOf("!xits-math.otf", "!texgyretermes-math.otf")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    val composeUi = "1.11.4"
    implementation("androidx.compose.ui:ui:$composeUi")
    implementation("androidx.compose.ui:ui-graphics:$composeUi")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeUi")
    implementation("androidx.compose.foundation:foundation:$composeUi")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeUi")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Offline OCR: Tesseract4Android is Apache-2.0 and runs fully on-device.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    // LaTeX in answers: MIT-licensed native typesetter (iosMath port, Latin Modern Math).
    implementation("com.github.gregcockroft:AndroidMath:v1.1.0") {
        // The library publishes its test stack (JUnit, androidx.test, Espresso, Guava) as
        // runtime dependencies. Its real needs (appcompat, annotation) are already here.
        isTransitive = false
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$composeUi")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$composeUi")
}
