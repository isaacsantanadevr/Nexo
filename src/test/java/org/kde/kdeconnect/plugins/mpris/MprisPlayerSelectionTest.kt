/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mpris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

class MprisPlayerSelectionTest {
    @Test
    fun commandsUseOnlyTheSelectedAnnouncedMprisPlayer() {
        val sentPackets = mutableListOf<NetworkPacket>()
        val device = mockk<Device> {
            every { sendPacket(any()) } answers { sentPackets += firstArg<NetworkPacket>() }
        }
        val context = mockk<Context>()
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(context) } returns mockk<SharedPreferences>(relaxed = true)
        val plugin = MprisPlugin().also {
            it.setContext(context, device)
        }
        fun announce(vararg ids: String) {
            plugin.onPacketReceived(NetworkPacket("kdeconnect.mpris").apply {
                this["playerList"] = ids.toList()
            })
        }
        fun sendSelected(selected: String?, action: String) {
            val target = MprisPlayerSelection.resolvePlayer(plugin, selected) ?: return
            when (action) {
                "pause" -> target.sendPause()
                "next" -> target.sendNext()
            }
        }

        announce("A", "B")
        sentPackets.clear()
        var selected: String? = null
        selected = MprisPlayerSelection.resolve(selected, plugin.playerList)
        sendSelected(selected, "pause")
        assertEquals(listOf("A"), sentPackets.map { it.getString("player") })
        assertEquals("Pause", sentPackets.single().getString("action"))

        selected = "B"
        sentPackets.clear()
        sendSelected(selected, "pause")
        sendSelected(selected, "next")
        assertEquals(listOf("B", "B"), sentPackets.map { it.getString("player") })
        assertEquals(listOf("Pause", "Next"), sentPackets.map { it.getString("action") })

        sentPackets.clear()
        MprisPlayerSelection.findExactPlayer(plugin, checkNotNull(selected))?.sendPause()
        assertEquals("B", sentPackets.single().getString("player"))

        announce("A", "B", "C")
        selected = MprisPlayerSelection.resolve(selected, plugin.playerList)
        assertEquals("B", selected)

        announce("A")
        selected = MprisPlayerSelection.resolve(selected, plugin.playerList)
        assertEquals("A", selected)
        assertNull(MprisPlayerSelection.resolve(selected, plugin.playerList - "A"))
        unmockkStatic(PreferenceManager::class)
    }
}
