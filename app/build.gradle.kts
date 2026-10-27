import java.security.MessageDigest

plugins { id("com.android.application") }
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-java:1.5.1")
    // Expose callback types upstream declares runtime-only, aligned to its stable dependency set.
    implementation("androidx.core:core:1.8.0")
    compileOnly("androidx.annotation:annotation:1.8.1")
}

val i18nConfiguration = groovy.json.JsonSlurper().parse(rootProject.file("i18n/config.json")) as Map<*, *>
val signingStore = providers.environmentVariable("WOZAI_ANDROID_KEYSTORE").orNull
val signingPassword = providers.environmentVariable("WOZAI_ANDROID_KEYSTORE_PASSWORD").orNull
require((signingStore == null) == (signingPassword == null)) {
    "Provide both WOZAI_ANDROID_KEYSTORE and WOZAI_ANDROID_KEYSTORE_PASSWORD for persistent signing"
}
val persistentSigning = signingStore != null

android {
    namespace = "dev.ghost.nearbyim"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ghost.nearbyim"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = providers.gradleProperty("testInstrumentationRunner")
            .orElse("dev.ghost.nearbyim.LocalizationInstrumentation").get()
        versionCode = 13
        versionName = i18nConfiguration["appVersion"] as String
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (persistentSigning) {
            create("persistent") {
                storeFile = file(signingStore!!)
                storeType = "PKCS12"
                storePassword = signingPassword
                keyAlias = "nearbyim"
                keyPassword = signingPassword
            }
        }
    }
    buildTypes {
        release {
            if (persistentSigning) signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName(if (persistentSigning) "persistent" else "debug")
            // Preserve the private UI hooks used by native instrumentation.
            proguardFiles("proguard-instrumentation.pro")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    sourceSets.getByName("main").java.srcDir(rootProject.file("third_party/noise-java/src"))
    // Public vectors and security checks are confined to the test APK.
    sourceSets.getByName("androidTest").java.srcDirs(
        rootProject.file("tests/noise-candidate/src"))
    // Every language remains available while switching offline, including Play bundles.
    bundle { language { enableSplit = false } }
}

val checkI18n by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        "tools/generate-i18n.py", "--check")
}
val checkAppIcons by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        "tools/check-app-icons.py")
}
val checkQrArtifact by tasks.registering {
    doLast {
        val artifact = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.single {
            it.moduleVersion.id.group == "com.google.zxing" && it.moduleVersion.id.name == "core"
        }
        val expected = rootProject.file("desktop/dependencies.txt").readLines().single {
            it.contains(" com/google/zxing/core/3.5.3/")
        }.substringBefore(" ")
        val actual = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(artifact.moduleVersion.id.version == "3.5.3" && actual == expected) { "QR dependency checksum mismatch" }
    }
}
tasks.named("preBuild").configure { dependsOn(checkI18n, checkAppIcons, checkQrArtifact) }
