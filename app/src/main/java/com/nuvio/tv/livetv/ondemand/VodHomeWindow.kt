package com.nuvio.tv.livetv.ondemand

/** The focused row and a small forward margin, independently for Movies and Series. */
internal fun vodHomeWindow(groups: List<VodCategory>, kind: VodKind, focused: String?, count: Int): List<VodCategory> {
    val sameKind = groups.filter { it.kind == kind }
    val start = sameKind.indexOfFirst { it.uid == focused }.coerceAtLeast(0)
    return sameKind.drop(start).take(count)
}
