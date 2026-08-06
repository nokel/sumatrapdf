plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Builds the Android reader. The engine comes from com.artifex.mupdf:fitz
// for now, which is the official MuPDF Android AAR; the same engine compiles
// out of the repo's own mupdf/ source via platform/java/Android.mk, and
// setting `-PmupdfLocal=true` on the build swaps the dependency for the
// locally-built libmupdf_java.so. See android/README.md for the swap.

android {
    namespace = "com.sumatrapdf.reader"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.sumatrapdf.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // No code shrinking in debug; the assembleDebug APK is the one
            // that gets pushed to the test device.
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // The document engine. mupdf is in the repo at mupdf/; this AAR is the
    // same source compiled for Android, just distributed as a Maven artifact.
    // To use the locally-built libmupdf_java.so instead, run with
    // `-PmupdfLocal=true` and drop the produced .so into app/src/main/jniLibs/.
    if (providers.gradleProperty("mupdfLocal").orNull == "true") {
        // Local-build path: the AAR is omitted, so the .so under jniLibs is
        // what loads. Copying the .so into jniLibs/ and the Java bindings from
        // mupdf/platform/java/src/com/artifex/mupdf/fitz/ into the app's
        // source tree is manual — see android/README.md. This branch only
        // drops the dependency.
        println("Using locally-built libmupdf_java.so from app/src/main/jniLibs/")
    } else {
        implementation("com.artifex.mupdf:fitz:1.28.0")
    }

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in unit tests; without a real
    // implementation every JSONObject call returns a default and any
    // test that reads JSON passes while checking nothing.
    testImplementation("org.json:json:20240303")
}
