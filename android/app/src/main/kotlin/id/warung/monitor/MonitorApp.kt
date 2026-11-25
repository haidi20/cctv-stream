package id.warung.monitor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt root. Kept deliberately small: no network init, login stays offline. */
@HiltAndroidApp
class MonitorApp : Application()