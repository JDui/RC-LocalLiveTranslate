plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}
android {
    val hyValidation = providers.gradleProperty("hyValidation").orNull == "true"
    if (hyValidation) {
        require(gradle.startParameter.taskNames.all { it.contains("HyValidation") }) {
            "The x86 validation configuration only accepts explicit HyValidation tasks"
        }
    }
    testBuildType = if (hyValidation) "hyValidation" else "debug"
    namespace = "com.jd.rc.localtranslate"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.jd.rc.localtranslate"
        minSdk = 28
        targetSdk = 36
        versionCode = 303
        versionName = "0.3.3"
        ndk { abiFilters += if (hyValidation) "x86_64" else "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    buildTypes {
        create("hyValidation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".hyvalidation"
            matchingFallbacks += "debug"
            ndk { abiFilters += "x86_64" }
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets["main"].apply {
        java.srcDir(rootProject.file(".tools/native/sherpa/kotlin"))
        jniLibs.srcDir(rootProject.file(".tools/native/sherpa/jniLibs"))
        jniLibs.srcDir(rootProject.file(".tools/native/hymt/jniLibs"))
        assets.srcDir(rootProject.file(".tools/native/assets"))
        if (providers.gradleProperty("bundledModels").orNull == "true") {
            assets.srcDir(rootProject.file(".tools/bundled-model-assets"))
        }
    }
    sourceSets["hyValidation"].jniLibs.srcDir(rootProject.file(".tools/native/hymt-validation/jniLibs"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("maven.repo.local", rootProject.layout.projectDirectory.dir(".tools/robolectric").asFile.path)
            if (providers.gradleProperty("verifyPreparedModels").orNull == "true") {
                it.systemProperty("preparedModelsRoot", rootProject.layout.projectDirectory.asFile.path)
            } else {
                it.exclude("**/PreparedModelImportTest*")
            }
        }
    }
    androidResources { noCompress += "zip" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.window)
    implementation(libs.media3.common)
    implementation(libs.datastore)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    kapt(libs.kotlin.metadata)
    implementation(libs.coroutines)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.window.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.runner)
}
