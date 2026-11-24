import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式发布签名（M9-4）：密钥与口令都在 .local/keystore.properties，而整个 .local/ 已被 gitignore，
// 所以仓库里没有密钥；CI 上靠 secret 还原同一份配置。文件不存在时（别人的 clone、临时环境）
// release 保持未签名，debug / benchmark 照旧能用，不会因为缺密钥就把构建卡死。
val keystorePropsFile = rootProject.file(".local/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "io.tinychat"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "top.tbpdt.tinychat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "1.0.8"
    }

    androidResources.localeFilters += listOf("zh", "b+zh+Hant", "ja", "en", "ru", "fr", "es")

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 签名方案交给 AGP 默认：minSdk 26 只需要 v2（实测产物即 v1=false / v2=true），
                // 不用手写 enableV1Signing —— 手写的值与 AGP 的判断打架时反而会漏签。
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // i18n 后各屏用 LocalContext.current 取字符串，部分在非 Composable 辅助函数里；
        // 改用 LocalResources 要动 30 多处、易出行为差异，且应用在配置变化时会重建界面，
        // 实际读不到陈旧资源。先关这条规则；新代码仍优先用 stringResource。
        disable += "LocalContextGetResourceValueCall"
    }

    // ⚠️ 仅供本机联调：从 .local/dev-key.txt（已 gitignore）注入开发用 Key。
    // 正式版本走 M8-6 的服务商与 API Key 页，Key 存 Keystore 加密后的 DataStore。
    val devKeyFile = rootProject.file(".local/dev-key.txt")
    val devApiKey = if (devKeyFile.isFile) {
        devKeyFile.readText().trim().replace("\\", "\\\\").replace("\"", "\\\"")
    } else {
        ""
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_API_KEY", "\"$devApiKey\"")
        }
        release {
            // 正式包**不注入**开发 Key（字段必须有，但发出去的包里恒为空串）
            buildConfigField("String", "DEV_API_KEY", "\"\"")
            // M9-4：第一个正式版起开 R8（体积 + 启动都靠它）。
            // 规则只写反射/约定那几类，见 app/proguard-rules.pro 的头注释。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        // 性能对照专用：release 的编译/运行语义（isDebuggable=false，走 ART 优化路径）+ 同一套 R8，
        // 但挂 debug 签名并注入开发 Key，这样能直接装到测试机上覆盖安装、量帧率。
        // ⚠️ 判断「卡不卡」必须用它，拿 debug 包量出来的是假的（实测 debug 50th 18ms、release 6ms）。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "DEV_API_KEY", "\"$devApiKey\"")
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    debugImplementation(project(":core:markdown"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // 崩溃兜底里的两段纯逻辑（脱敏、循环计数、保留份数）用普通 JUnit 就能测，
    // 不需要 Robolectric —— 它们刻意不碰 Context，见 CrashReporter / CrashLoop 的类注释。
    testImplementation(libs.junit)
}
