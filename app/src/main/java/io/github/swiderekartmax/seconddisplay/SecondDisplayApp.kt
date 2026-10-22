package io.github.swiderekartmax.seconddisplay

import android.app.Application

/** Installs the crash recorder before any activity, service or receiver runs. */
class SecondDisplayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProblemReport.installCrashRecorder(this)
    }
}
