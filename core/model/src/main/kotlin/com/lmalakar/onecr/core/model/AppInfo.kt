package com.lmalakar.onecr.core.model

/** Build information surfaced in the Dashboard and Settings screens. */
data class AppInfo(
    val versionName: String,
    val versionCode: String,
    val buildType: String,
)
