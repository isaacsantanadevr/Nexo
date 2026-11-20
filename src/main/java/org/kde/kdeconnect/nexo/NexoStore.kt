// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.nexo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import java.util.UUID

/** Small persistent projection of real upstream transfer events. Never drives the protocol. */
object NexoStore {
    private val sessionId = UUID.randomUUID().toString()
    private fun prefs(c: Context) = c.getSharedPreferences("nexo", Context.MODE_PRIVATE)
    @Synchronized fun history(c: Context): JSONArray = JSONArray(prefs(c).getString("history", "[]"))
    @Synchronized fun event(c: Context, message: String) {
        val a = JSONArray().put(JSONObject().put("text", message).put("time", System.currentTimeMillis()))
        val old = history(c)
        for (i in 0 until minOf(49, old.length())) a.put(old.get(i))
        prefs(c).edit().putString("history", a.toString()).apply()
    }
    @Synchronized fun transfers(c: Context): JSONArray {
        val p = prefs(c)
        val rows = JSONArray(p.getString("transfers", "[]"))
        var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("status") in ACTIVE_STATUSES && row.optString("session") != sessionId) {
                row.put("status", "Interrompido").put("updatedAt", System.currentTimeMillis()).put("progressKnown", false)
                event(c, "${row.optString("direction")} · ${row.optString("name", "Arquivo")} · Interrompido")
                changed = true
            }
        }
        if (changed) p.edit().putString("transfers", rows.toString()).apply()
        return rows
    }
    @Synchronized fun rememberRetryUri(c: Context, deviceId: String, name: String, uri: String) {
        if (name.isBlank()) return
        prefs(c).edit().putString("retry_uri:$deviceId:$name", uri).apply()
    }
    @Synchronized fun retryUri(c: Context, deviceId: String, name: String): String? =
        prefs(c).getString("retry_uri:$deviceId:$name", null)
    @Synchronized fun transfer(
        d: Device,
        id: String,
        name: String,
        direction: String,
        progress: Int,
        status: String,
        progressKnown: Boolean? = null,
        fileUri: String? = null,
    ) {
        val old = transfers(d.context)
        val a = JSONArray()
        var previous = ""
        var existing: JSONObject? = null
        for (i in 0 until old.length()) {
            val r = old.getJSONObject(i)
            if (r.optString("id") == id) { previous = r.optString("status"); existing = r }
        }
        val now = System.currentTimeMillis()
        val row = JSONObject().put("id", id).put("device", d.deviceId).put("deviceName", d.name)
            .put("name", name).put("direction", direction).put("progress", progress.coerceIn(0, 100))
            .put("progressKnown", progressKnown ?: existing?.optBoolean("progressKnown", false) ?: false)
            .put("status", status).put("time", existing?.optLong("time", now) ?: now)
            .put("updatedAt", now).put("session", sessionId)
        (fileUri ?: existing?.optString("fileUri")?.takeIf { it.isNotBlank() })?.let { row.put("fileUri", it) }
        a.put(row)
        for (i in 0 until old.length()) if (a.length() < 60 && old.getJSONObject(i).optString("id") != id) a.put(old.get(i))
        prefs(d.context).edit().putString("transfers", a.toString()).apply()
        if (status != previous && status in TERMINAL_STATUSES) {
            event(d.context, "$direction · $name · $status")
        }
    }

    @Synchronized fun interrupted(d: Device, id: String, name: String, direction: String, status: String = "Interrompido") {
        transfer(d, id, name, direction, 0, status, progressKnown = false)
    }

    private val ACTIVE_STATUSES = setOf("Preparando", "Enviando", "Recebendo", "Em andamento")
    private val TERMINAL_STATUSES = setOf("Sucesso reportado pelo KDE Connect", "Falhou", "Cancelado", "Interrompido")
}
