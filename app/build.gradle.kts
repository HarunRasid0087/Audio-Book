plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.offlinetts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.offlinetts"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // MediaSession + MediaStyle notification (lock-screen transport controls)
    implementation("androidx.media:media:1.7.0")

    // PDF text extraction (offline, pure-JVM port of Apache PDFBox)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // EPUB parsing (offline) + HTML stripping
    // NOTE: JitPack reported `Tag or commit 'epublib-3.1' not found` — the previous
    // version string was invalid. Pinned to a commit hash, which JitPack always resolves.
    // To use a release tag instead, replace the version with a tag that exists in the repo.
    implementation("com.github.psiegman:epublib:645a3e4") {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("org.jsoup:jsoup:1.17.2")

    // OCR for scanned/image-only PDFs (bundled model = fully offline)
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
