package com.slipe.jarvis

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Lembretes de água (a cada X minutos, das 07:00 às 21:00) e contador do dia. */
object Water {
    fun on(c: Context) = Core.prefs(c).getBoolean("w_on", true)
    fun goal(c: Context) = Core.prefs(c).getInt("w_goal", 16)
    fun every(c: Context) = Core.prefs(c).getInt("w_every", 90).coerceIn(15, 360)

    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 4, Intent(c, WaterReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun scheduleNext(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c))
        if (!on(c)) return
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, every(c))
        val reached = Core.waterN(c) >= goal(c)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        if (reached || h >= 21 || h < 7) {
            val t = Calendar.getInstance()
            t.set(Calendar.HOUR_OF_DAY, 7)
            t.set(Calendar.MINUTE, 0)
            t.set(Calendar.SECOND, 0)
            t.set(Calendar.MILLISECOND, 0)
            if (t.timeInMillis <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis = t.timeInMillis
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(c))
        } catch (e: Exception) {
        }
    }
}

class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Water.on(c) && h in 7..20 && Core.waterN(c) < Water.goal(c)) {
            Notifier.notifyWater(c)
        }
        Water.scheduleNext(c)
    }
}

class WaterAddReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Core.addWater(c, 1)
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(88)
    }
}
