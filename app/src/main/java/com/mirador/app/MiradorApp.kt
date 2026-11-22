package com.mirador.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Verrouille Mirador dès que l'appli passe en arrière-plan (au-delà de quelques secondes). */
class MiradorApp : Application() {

    private var started = 0
    private var backgroundAt = 0L

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > Lock.GRACE_MS) {
                    Lock.relock()
                }
            }

            override fun onActivityStopped(a: Activity) {
                if (--started == 0 && !a.isChangingConfigurations) backgroundAt = System.currentTimeMillis()
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
