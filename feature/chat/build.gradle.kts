plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.tinychat.feature.chat"
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
    // api：ChatViewModel 对外暴露 SpectrumAnalyser，宿主（:app）要能引用这个类型
    api(project(":core:audio"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:markdown"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 分享长图（M8-2）：离屏 ComposeView 需要手动挂 ViewTreeLifecycleOwner
    implementation(libs.androidx.lifecycle.runtime)
    // 附件选择（M4-1/2）：rememberLauncherForActivityResult + Photo Picker 契约
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)

    testImplementation(libs.junit)
}
