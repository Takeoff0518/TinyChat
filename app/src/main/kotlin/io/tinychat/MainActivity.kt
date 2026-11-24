package io.tinychat

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import io.tinychat.data.settings.UserSettings
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcThemeMode
import java.util.Locale

/** 冷启动直接渲染会话页。 */
class MainActivity : ComponentActivity() {
    private var splashLanding: SplashLanding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val landing = if (savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()) {
            SplashLanding().also { splash.setOnExitAnimationListener(it::start) }
        } else {
            null
        }
        splashLanding = landing
        setContent {
            // 外观（M8-4）：读设置里的三档，改完立刻重建配色
            val settings = remember { UserSettings(applicationContext) }
            val mode by settings.themeMode.collectAsState(initial = "system")
            val language by settings.appLanguage.collectAsState(initial = "zh-CN")
            val currentConfiguration = LocalConfiguration.current
            val locale = remember(language) { Locale.forLanguageTag(language) }
            val configuration = remember(currentConfiguration, locale) {
                Configuration(currentConfiguration).apply { setLocales(LocaleList(locale)) }
            }
            val context = remember(configuration) {
                val activity = this@MainActivity
                val localized = activity.createConfigurationContext(configuration)
                object : ContextWrapper(activity) {
                    override fun getResources(): Resources = localized.resources
                }
            }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                TcTheme(mode = when (mode) {
                    "light" -> TcThemeMode.LIGHT
                    "dark" -> TcThemeMode.DARK
                    else -> TcThemeMode.SYSTEM
                }) {
                    val lightBackground = TcTheme.palette.bg.luminance() > 0.5f
                    SideEffect {
                        (application as TinyChatApplication).generation.languageTag = language
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightBackground
                            isAppearanceLightNavigationBars = lightBackground
                        }
                    }
                    TinyChatApp()
                }
            }
        }
    }

    override fun onDestroy() {
        splashLanding?.dispose()
        super.onDestroy()
    }
}
