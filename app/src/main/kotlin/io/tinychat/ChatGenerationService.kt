package io.tinychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.Job

/** Main-thread ownership of the operation and its foreground service. */
internal class ChatGenerationLifetime(private val application: TinyChatApplication) {
    private val main = Handler(Looper.getMainLooper())
    private var activeJob: Job? = null
    var service: ChatGenerationService? = null
    var languageTag: String = "zh-CN"
    val hasWork: Boolean get() = activeJob?.isCompleted == false

    fun keepAlive(job: Job) {
        activeJob = job
        job.invokeOnCompletion {
            main.post {
                if (activeJob === job) {
                    activeJob = null
                    service?.finishIfIdle()
                }
            }
        }
        try {
            ContextCompat.startForegroundService(application, Intent(application, ChatGenerationService::class.java))
        } catch (error: IllegalStateException) {
            Log.w("TinyChat", "Android did not allow foreground generation", error)
        } catch (error: SecurityException) {
            Log.w("TinyChat", "Foreground generation service is unavailable", error)
        }
    }

    fun cancel() { activeJob?.cancel() }
}

class ChatGenerationService : Service() {
    private val generation get() = (application as TinyChatApplication).generation

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        generation.service = this
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: IllegalStateException) {
            Log.w("TinyChat", "Android declined foreground generation", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        } catch (error: SecurityException) {
            Log.w("TinyChat", "Foreground generation service is unavailable", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        finishIfIdle()
        return START_NOT_STICKY
    }

    internal fun finishIfIdle() {
        if (generation.hasWork) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        generation.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (generation.service === this) generation.service = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val config = Configuration(resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(generation.languageTag)))
        }
        val localized = createConfigurationContext(config)
        val label = localized.getString(R.string.app_chat_generation)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        val chatIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openChat = PendingIntent.getActivity(this, 0, chatIntent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_generation_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(label)
            .setContentIntent(openChat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "chat_generation"
        const val NOTIFICATION_ID = 1001
    }
}
