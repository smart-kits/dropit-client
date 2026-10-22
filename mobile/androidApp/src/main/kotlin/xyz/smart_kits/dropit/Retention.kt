package xyz.smart_kits.dropit

import xyz.smart_kits.dropit.shared.DAY_MS

/** How long the service keeps what is sent to it, on this account's plan (the service's own numbers once it has said them) */
suspend fun App.retentionMs(): Long = (if (account.me()?.paid == true) plans.paid.days else plans.free.days) * DAY_MS
