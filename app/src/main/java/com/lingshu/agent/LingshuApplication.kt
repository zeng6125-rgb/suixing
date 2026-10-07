package com.lingshu.agent

import android.app.Application
import com.lingshu.agent.core.Core

class LingshuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
