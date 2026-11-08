plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val linksyPackage = "app.linksy.mg"
// Official Linksy signing-cert SHA-256 (hex, colons allowed). Empty = binding disabled.
val linksyCertSha256 = (project.findProperty("linksyCertSha256") as String? ?: "").trim()
require(Regex("[0-9A-Fa-f:]*").matches(linksyCertSha256)) {
    "linksyCertSha256 must be hex (colons allowed)"
}

android {
    namespace = "app.linksy.mg.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.linksy.mg.control"
        // 28: signing-certificate lookup (GET_SIGNING_CERTIFICATES) is what caller pinning relies on.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LINKSY_PACKAGE", "\"$linksyPackage\"")
        buildConfigField("String", "LINKSY_CERT_SHA256", "\"$linksyCertSha256\"")
        manifestPlaceholders["linksyPackage"] = linksyPackage
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
}
