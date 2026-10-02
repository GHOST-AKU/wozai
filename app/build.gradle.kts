plugins { id("com.android.application") }

val i18nConfiguration = groovy.json.JsonSlurper().parse(rootProject.file("i18n/config.json")) as Map<*, *>

android {
    namespace = "dev.ghost.nearbyim"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ghost.nearbyim"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "dev.ghost.nearbyim.LocalizationInstrumentation"
        versionCode = 4
        versionName = i18nConfiguration["appVersion"] as String
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
    // Every language remains available while switching offline, including Play bundles.
    bundle { language { enableSplit = false } }
}

val checkI18n by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        "tools/generate-i18n.py", "--check")
}
tasks.named("preBuild").configure { dependsOn(checkI18n) }
