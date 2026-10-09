package com.diu.privacyguard

import android.content.Context

/** App version read from the installed package, so it always matches build.gradle.kts. */
object BuildConfigInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
