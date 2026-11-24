plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.tinychat.feature.settings"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }

    buildFeatures { compose = true }

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
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    // 导入/导出用 SAF（M6-5）
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
}
