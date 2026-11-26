package com.beauahill.hilltv

import android.app.Application
import com.beauahill.hilltv.model.AppModel

/** Holds the one [AppModel], so the session outlives the activity being rebuilt. */
class HillTVApplication : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
    }
}
