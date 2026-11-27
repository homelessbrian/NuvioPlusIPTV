package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/** Play Store manages updates in this flavor. */
internal fun Context.updateStoreForSetupSync(): DataStore<Preferences>? = null
