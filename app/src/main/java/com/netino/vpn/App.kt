package com.netino.vpn

import android.app.Application
import android.content.Context
import com.netino.vpn.core.XrayCore
import com.netino.vpn.data.Repository
import com.netino.vpn.service.ConnectionNotifier
import com.netino.vpn.service.CrashReporter
import com.netino.vpn.service.SubscriptionWorker
import com.netino.vpn.service.VpnController

class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Locales.wrap(base))

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Repository.init(this)
        XrayCore.init(this)
        VpnController.init(this)
        Repository.onStorageError = { VpnController.logText("Storage: $it") }
        CrashReporter.takePrevious(this).forEach { VpnController.logText(it) }
        ConnectionNotifier.createChannel(this)
        SubscriptionWorker.schedule(this)
    }
}
