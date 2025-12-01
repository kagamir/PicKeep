plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CI 环境下使用的签名信息，从环境变量中读取（GitHub Actions 的 secrets 会自动注入为环境变量）
val envKeystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val envKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")

android {
    namespace = "net.kagamir.pickeep"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.kagamir.pickeep"
        minSdk = 31
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 仅在 CI 中依赖外部 keystore（app/release.jks），本地如果没有环境变量可以继续用 debug 构建
        if (
            envKeystorePassword != null &&
            envKeyAlias != null &&
            envKeyPassword != null
        ) {
            create("release") {
                // keystore 文件路径相对于 app/ 模块根目录
                storeFile = file("release.jks")
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 关联上面的 release 签名配置，在 CI 中生成已签名的 release APK
            // 只在环境变量存在时才使用 release 签名配置,否则使用 debug 签名
            if (
                envKeystorePassword != null &&
                envKeyAlias != null &&
                envKeyPassword != null
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    
    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Security
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)
    
    // Bouncy Castle for crypto
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    
    // ZXing for QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}