package com.lmalakar.onecr

import android.app.Application
import android.content.Context
import com.lmalakar.onecr.core.model.AppInfo

/**
 * Minimal service-locator container for M1. Grows with the milestones
 * (privilege providers in M2, recorder + repositories in M3/M4, engines in M5).
 * Hilt can replace this later if the graph becomes non-trivial.
 */
class AppContainer(appContext: Context) {

    val appInfo: AppInfo = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toString(),
        buildType = BuildConfig.BUILD_TYPE,
    )
}
