plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.jd.rc.localtranslate.macrobenchmark"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.killExistingPerfettoRecordings"] = "false"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.android.test.junit)
    implementation(libs.android.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.tracing.perfetto)
    implementation(libs.tracing.binary)
    implementation(libs.tracing.handshake)
}
