plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versions = groovy.json.JsonSlurper().parse(rootProject.file("i18n/config.json")) as Map<*, *>
val signingStore = providers.environmentVariable("WOZAI_ANDROID_KEYSTORE").orNull
val signingPassword = providers.environmentVariable("WOZAI_ANDROID_KEYSTORE_PASSWORD").orNull
require((signingStore == null) == (signingPassword == null)) {
    "Provide both persistent Android signing variables"
}

// Kotlin's Java source discovery ignores SourceDirectorySet exclusions. Feed both
// compilers the same generated, byte-for-byte view of the shared sources instead.
val sharedJava by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) { exclude("**/MainActivity.java") }
    from(rootProject.file("third_party/noise-java/src"))
    into(layout.buildDirectory.dir("generated/sharedJava"))
}

val meshJava by tasks.registering(Sync::class) {
    from(rootProject.file("mesh/android-radio/src")) { exclude("**/RadioLab.java") }
    into(layout.buildDirectory.dir("generated/meshJava"))
}
val meshNative by tasks.registering(Exec::class) {
    inputs.files(rootProject.fileTree("mesh/src"), rootProject.file("mesh/Cargo.toml"), rootProject.file("mesh/Cargo.lock"), rootProject.file("tools/package-android-mesh.py"))
    inputs.file(rootProject.file("mesh/target/aarch64-linux-android/release/mesh-lab"))
    outputs.dir(layout.buildDirectory.dir("generated/meshNative"))
    commandLine("python3", rootProject.file("tools/package-android-mesh.py"), "--output",
        layout.buildDirectory.file("generated/meshNative/arm64-v8a/libmesh_core.so").get().asFile)
}
val meshNotices by tasks.registering(Sync::class) {
    from(rootProject.file("mesh/vendor-licenses")) { into("vendor-licenses") }
    from(rootProject.file("mesh/THIRD_PARTY.md"))
    from(rootProject.file("LICENSE"))
    into(layout.buildDirectory.dir("generated/meshAssets/mesh-notices"))
}

android {
    namespace = "dev.ghost.nearbyim"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ghost.nearbyim.next"
        minSdk = 26
        targetSdk = 36
        versionCode = (versions["buildNumber"] as Number).toInt()
        versionName = versions["appVersion"] as String
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (signingStore != null) create("persistent") {
            storeFile = file(signingStore)
            storeType = "PKCS12"
            storePassword = signingPassword
            keyAlias = "nearbyim"
            keyPassword = signingPassword
        }
    }
    buildTypes {
        release {
            if (signingStore != null) signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    sourceSets.getByName("main") {
        java.srcDir(layout.buildDirectory.dir("generated/sharedJava"))
        res.srcDirs(rootProject.file("app/src/main/res"), "src/main/res")
        java.srcDir(layout.buildDirectory.dir("generated/meshJava"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/meshNative"))
        assets.srcDir(layout.buildDirectory.dir("generated/meshAssets"))
    }
    packaging { jniLibs {
        useLegacyPackaging = true
        // This is an already stripped executable; preserve the bound image byte-for-byte.
        keepDebugSymbols += "**/libmesh_core.so"
    } }
    bundle { language { enableSplit = false } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
kotlin { jvmToolchain(17) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(sharedJava, meshJava)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-java:1.5.1")
    implementation("androidx.core:core:1.16.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.named("preBuild").configure {
    dependsOn(meshJava, meshNative, meshNotices, ":app:checkI18n", ":app:checkAppIcons", ":app:checkQrArtifact")
}
