package com.nuvio.tv.core.party

/** Party metadata must not publish a portal's tokenized artwork or private command-derived filename. */
internal fun redactStalkerPartyMedia(media: PartyMedia, isStalker: Boolean): PartyMedia =
    if (!isStalker) media else media.copy(
        poster = null,
        background = null,
        logo = null,
        source = media.source?.copy(filename = null),
    )
