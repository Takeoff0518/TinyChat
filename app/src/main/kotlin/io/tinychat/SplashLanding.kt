package io.tinychat

import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreenViewProvider
import io.tinychat.designsystem.TcMotion

/** Reveals the first app frame while leaving the greeting mark in place. */
internal class SplashLanding {
    private var splashView: SplashScreenViewProvider? = null

    fun start(splash: SplashScreenViewProvider) {
        splashView = splash
        splash.view.animate()
            .alpha(0f)
            .setDuration(TcMotion.Fade.toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(::dispose)
            .start()
    }

    fun dispose() {
        val splash = splashView ?: return
        splashView = null
        splash.view.animate().cancel()
        splash.remove()
    }
}
