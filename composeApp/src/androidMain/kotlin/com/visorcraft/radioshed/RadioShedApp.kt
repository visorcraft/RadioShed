package com.visorcraft.radioshed

import android.app.Application
import com.visorcraft.radioshed.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level

class RadioShedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RadioShedApp)
            androidLogger(Level.INFO)
        }
    }
}
