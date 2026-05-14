plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.speakassist"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.speakassist"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST" // 最早解决的冲突文件
            excludes += "META-INF/DEPENDENCIES" // 第二次解决的 httpcomponents 冲突
            excludes += "META-INF/io.netty.versions.properties" // 新增：解决本次 netty 冲突
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.firebase.appdistribution.gradle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.qmuiteam:qmui:2.0.1") // 添加 QMUI 依赖

    // 网络请求（向AI接口发送指令）
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.1")

    // JSON解析
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // 自定义键盘
    implementation("androidx.appcompat:appcompat:1.4.0")

    // ViewModel + Compose 桥接（核心依赖）
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // 讯飞语音 SDK（从 libs 目录加载）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Vosk 离线语音识别（唤醒词 + 本地命令识别）
    implementation("com.alphacephei:vosk-android:0.3.47")
}