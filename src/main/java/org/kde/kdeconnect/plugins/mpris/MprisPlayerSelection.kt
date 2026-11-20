/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mpris

/** Resolves the persisted MPRIS id against the identifiers currently announced by the peer. */
internal object MprisPlayerSelection {
    fun resolve(selectedPlayerId: String?, announcedPlayerIds: List<String>): String? =
        selectedPlayerId?.takeIf(announcedPlayerIds::contains) ?: announcedPlayerIds.firstOrNull()

    fun resolvePlayer(plugin: MprisPlugin, selectedPlayerId: String?): MprisPlugin.MprisPlayer? =
        resolve(selectedPlayerId, plugin.playerList)?.let(plugin::getPlayerStatus)

    fun findExactPlayer(plugin: MprisPlugin, playerId: String): MprisPlugin.MprisPlayer? =
        playerId.takeIf(plugin.playerList::contains)?.let(plugin::getPlayerStatus)
}
