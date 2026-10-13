package ru.storage.app

import android.app.Application

/** Подключает Firebase администратора (если он задал свой) раньше, чем что-либо обратится к базе. */
class LagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConnCfg.init(this)
    }
}
