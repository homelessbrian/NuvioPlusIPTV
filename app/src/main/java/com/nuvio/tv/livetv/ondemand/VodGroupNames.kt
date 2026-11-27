package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.livetv.model.ChannelNameEditor
import com.nuvio.tv.livetv.model.LiveTvSettings

/** Display policy only: provider names remain unchanged in storage, queries and parental checks. */
data class VodGroupNames(val enabled: Boolean, val removals: String) {
    private val terms = if (enabled) ChannelNameEditor.terms(removals) else emptyList()

    fun display(providerName: String, manualName: String? = null): String =
        manualName?.takeIf { it.isNotBlank() }
            ?: ChannelNameEditor.clean(providerName, terms).takeIf { it.isNotBlank() }
            ?: providerName

    companion object {
        fun from(settings: LiveTvSettings) = VodGroupNames(settings.cleanVodGroupNames, settings.nameRemovals)
    }
}
