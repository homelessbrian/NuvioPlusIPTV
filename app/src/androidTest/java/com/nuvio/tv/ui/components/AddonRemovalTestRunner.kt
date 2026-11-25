package com.nuvio.tv.ui.components

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs the synthetic UI fixture without starting Nuvio's injected repositories or sync jobs. */
class AddonRemovalTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(loader, Application::class.java.name, context)
}
