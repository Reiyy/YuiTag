plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutLibraries)
}

android {
    namespace = "com.reiyy.yuitag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reiyy.yuitag"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0-beta.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {}
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

// Room schema 导出目录（用于未来编写正式迁移）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // osmdroid (map)
    implementation(libs.osmdroid.android)

    // Lottie Compose (animation)
    implementation(libs.lottie.compose)

    // 拖拽排序（sh.calvin.reorderable）
    implementation(libs.reorderable)

    // Bouncy Castle (secp224r1, AES-GCM for FindMy decryption)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // Gson (theme config JSON serialization)
    implementation("com.google.code.gson:gson:2.11.0")
    // OkHttp (Apple GrandSlam & FindMy direct API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AboutLibraries (自动化开源许可)
    implementation(libs.aboutlibraries.compose.m3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
