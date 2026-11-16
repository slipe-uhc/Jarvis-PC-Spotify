package com.slipe.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Guardião: a cada ~15 min confere se a escuta ainda está de pé e tenta religar. */
object Watchdog {
    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 6, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun start(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5 * 60000L, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi(c))
        } catch (e: Exception) {
        }
    }

    fun stop(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c))
    }

    /** Tenta subir o serviço; se o Android não deixar, avisa para tocar e religar. */
    fun revive(c: Context) {
        if (!Core.voice(c) || JarvisService.instance != null) return
        try {
            c.startForegroundService(Intent(c, JarvisService::class.java))
        } catch (e: Exception) {
            Core.prefs(c).edit().putString("last_err", "serviço de escuta parou; abra o Jarvis para religar").apply()
            Notifier.notifyTap(c, "religar a escuta", Intent(c, MainActivity::class.java))
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Watchdog.revive(c)
        Scheduler.scheduleNext(c)
        Water.scheduleNext(c)
    }
}
