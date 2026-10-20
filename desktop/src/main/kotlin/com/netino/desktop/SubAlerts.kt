package com.netino.desktop

import com.netino.vpn.data.Repository
import com.netino.vpn.data.Subscription

/**
 * Warns before a subscription stops working (as on the phone): 80 % of its data used, 3 days left, out of data,
 * expired, from the panel's subscription-userinfo header. Each level is announced once; a renewal starts over.
 */
object SubAlerts {
    private const val DAY = 86_400_000L

    fun level(sub: Subscription, now: Long = System.currentTimeMillis()): String? {
        val msLeft = sub.expire * 1000 - now
        return when {
            sub.expire > 0 && msLeft <= 0 -> "expired"
            sub.total > 0 && sub.used >= sub.total -> "used100"
            sub.expire > 0 && msLeft < 3 * DAY -> "days3"
            sub.total > 0 && sub.used >= sub.total * 8 / 10 -> "used80"
            else -> null
        }
    }

    fun check() {
        val now = System.currentTimeMillis()
        val old = Repository.settings.value.subAlerts
        val next = mutableMapOf<String, String>()
        for (sub in Repository.subscriptions.value.filter { !it.builtIn }) {
            val level = level(sub, now) ?: continue
            next[sub.id] = level
            if (old[sub.id] != level) Controller.notify(title(sub, level, now), L.t("تمدید کنید یا اشتراک دیگری اضافه کنید.", "Renew it or add another subscription."))
        }
        if (next != old) Repository.updateSettings { it.copy(subAlerts = next) }
    }

    fun title(sub: Subscription, level: String, now: Long = System.currentTimeMillis()): String = when (level) {
        "expired" -> L.t("اشتراک «${sub.name}» تمام شده", "Subscription \"${sub.name}\" has expired")
        "used100" -> L.t("حجم اشتراک «${sub.name}» تمام شده", "Subscription \"${sub.name}\" is out of data")
        "days3" -> L.t("${(sub.expire * 1000 - now) / DAY + 1} روز تا پایان «${sub.name}»", "${(sub.expire * 1000 - now) / DAY + 1} days left on \"${sub.name}\"")
        else -> L.t("۸۰٪ حجم «${sub.name}» مصرف شده", "80% of \"${sub.name}\" used")
    }
}
