package com.iptv.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference

/** Uygulama: ekranda olan sayfayı bilir (uyku zamanlayıcısı için) */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SleepTimer.app = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { SleepTimer.top = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

/** Belirli süre sonra uygulamayı kapatır; 1 dakika önce uyarır */
object SleepTimer {
    var app: Application? = null
    var top: WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L

    val active get() = endAt > System.currentTimeMillis()
    val minutesLeft get() = ((endAt - System.currentTimeMillis() + 59_999) / 60_000).toInt()

    private val warn = Runnable {
        app?.let { Toast.makeText(it, "⏾ Uygulama 1 dakika sonra kapanacak", Toast.LENGTH_LONG).show() }
    }
    private val fire = Runnable {
        endAt = 0
        top?.get()?.finishAffinity()
    }

    fun start(minutes: Int) {
        cancel()
        endAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(warn, minutes * 60_000L - 60_000L)
        handler.postDelayed(fire, minutes * 60_000L)
    }

    fun cancel() {
        endAt = 0
        handler.removeCallbacks(warn)
        handler.removeCallbacks(fire)
    }
}
