plugins { id("com.android.application") }

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
        versionCode = 8
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
tasks.named("preBuild").configure { dependsOn(checkI18n, checkAppIcons) }
