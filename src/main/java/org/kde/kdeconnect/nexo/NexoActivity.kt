// SPDX-License-Identifier: GPL-2.0-or-later
package org.kde.kdeconnect.nexo

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.webkit.*
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.*
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.mpris.MprisMediaSession
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.plugins.mpris.MprisPlayerSelection
import org.kde.kdeconnect.plugins.presenter.PresenterPlugin
import org.kde.kdeconnect.plugins.systemvolume.Sink
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin
import org.kde.kdeconnect.plugins.runcommand.CommandInfo
import org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect.plugins.share.ShareJob
import org.kde.kdeconnect.ui.MainActivity
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class NexoActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("nexo", MODE_PRIVATE) }
    private val kde get() = KdeConnect.getInstance()
    private var selected = ""
    private var fileTarget = ""
    private var retryName: String? = null
    private var lastState = ""
    private var ready = false
    private var commandForwarded = false
    private var sessionMediaSelection = ""
    private var observedRunCommandPlugin: RunCommandPlugin? = null
    private val runCommandsChanged = RunCommandPlugin.CommandsChangedCallback { handler.post { publish() } }
    private var observedMprisPlugin: MprisPlugin? = null
    private val mprisStatusChanged: () -> Unit = { handler.post { publish() } }
    private val mprisPlayersChanged: () -> Unit = { handler.post { publish() } }
    private var observedSystemVolumePlugin: SystemVolumePlugin? = null
    private val observedVolumeSinks = mutableSetOf<Sink>()
    private val systemVolumeSinksChanged = object : SystemVolumePlugin.SinkListener {
        override fun sinksChanged() { handler.post { refreshVolumeSinkListeners(); publish() } }
    }
    private val systemVolumeUpdated = object : Sink.UpdateListener {
        override fun updateSink(sink: Sink) { handler.post { publish() } }
    }
    private val priorConnection = mutableMapOf<String, Boolean>()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val d = kde.getDevice(fileTarget)
            val plugin = d?.takeIf { it.isPaired && it.isReachable }?.getPlugin<SharePlugin>()
            if (plugin == null) notice("Envio cancelado: dispositivo desconectado ou compartilhamento indisponível.")
            else io.execute {
                try {
                    val expected = retryName
                    for (uri in uris) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        if (checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            NexoStore.rememberRetryUri(this, fileTarget, name ?: expected ?: "", uri.toString())
                        }
                    }
                    val selectedUris = if (expected != null) uris.filter { uri ->
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> c.moveToFirst() && c.getString(0) == expected } == true
                    } else uris
                    if (selectedUris.isEmpty()) runOnUiThread { notice("Selecione novamente o arquivo ${expected ?: "desejado"}.") }
                    else plugin.sendFiles(selectedUris)
                } catch (e: Exception) {
                    NexoStore.event(this, "Falha ao abrir arquivo: ${e.localizedMessage}")
                    runOnUiThread { notice("Não foi possível abrir ou enviar o arquivo.") }
                } finally { retryName = null }
            }
        } else retryName = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        kde.reloadAllDevicePlugins()
        BackgroundService.ForceRefreshConnections(this)
        publish()
    }
    override fun attachBaseContext(base: android.content.Context) {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag("pt-BR"))
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        selected = prefs.getString("device", "") ?: ""
        web = WebView(this)
        setContentView(web)
        // Immersive bars overlay the WebView when temporarily revealed. Only reserve
        // cutout and IME space so content does not jump with transient system bars.
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                cutout.left,
                cutout.top,
                cutout.right,
                maxOf(cutout.bottom, ime.bottom),
            )
            insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.addJavascriptInterface(Bridge(), "Nexo")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val u = request.url
                if (u.scheme == "https" && u.host == "nexo.local") {
                    val name = u.path?.removePrefix("/") ?: ""
                    if (name in listOf("index.html", "app.js", "icons.js", "style.css", "notebook.webp")) {
                        val mime = when { name.endsWith(".js") -> "text/javascript"; name.endsWith(".css") -> "text/css"; name.endsWith(".webp") -> "image/webp"; else -> "text/html" }
                        return WebResourceResponse(mime, "UTF-8", assets.open("nexo/$name"))
                    }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageFinished(view: WebView, url: String) { ready = true; publish() }
        }
        web.loadUrl("https://nexo.local/index.html")
        BackgroundService.Start(this)
        kde.addDeviceListChangedCallback("nexo") { handler.post { publish() } }
        // KdeConnect restores TrustedDevices before this Activity starts. Ask its
        // discovery service to refresh once on entry; pairing remains stored by KDE.
        BackgroundService.ForceRefreshConnections(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { web.evaluateJavascript("back()") { if (it == "false") finish() } }
        })
        requestPermissionsIfNeeded()
    }
    private fun requestPermissionsIfNeeded() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) p.add("android.permission.ACCESS_LOCAL_NETWORK")
        if (Build.VERSION.SDK_INT < 30) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = p.filter { androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    private val ticker = object : Runnable {
        // Device/link and pairing changes arrive through KdeConnect's callback above.
        // This slow refresh only covers plugin status that has no dedicated callback.
        override fun run() {
            publish()
            val transferActive = NexoStore.transfers(this@NexoActivity).let { rows ->
                (0 until rows.length()).any { rows.getJSONObject(it).optString("status") in setOf("Preparando", "Enviando", "Recebendo", "Em andamento") }
            }
            handler.postDelayed(this, if (transferActive) 1_000 else 5_000)
        }
    }
    private fun configureImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
    override fun onResume() { super.onResume(); configureImmersive(true); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); configureImmersive(false); super.onPause() }
    override fun onDestroy() {
        kde.removeDeviceListChangedCallback("nexo")
        observedRunCommandPlugin?.removeCommandsUpdatedCallback(runCommandsChanged)
        observedRunCommandPlugin = null
        observedMprisPlugin?.removePlayerStatusUpdatedHandler("nexo")
        observedMprisPlugin?.removePlayerListUpdatedHandler("nexo")
        observedMprisPlugin = null
        detachSystemVolume()
        handler.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("Nexo"); web.destroy(); io.shutdown()
        super.onDestroy()
    }
    private fun device() = kde.getDevice(selected)
    private fun online() = device()?.takeIf { it.isPaired && it.isReachable }
    private fun detachSystemVolume() {
        observedSystemVolumePlugin?.removeSinkListener(systemVolumeSinksChanged)
        observedVolumeSinks.forEach { it.removeListener(systemVolumeUpdated) }
        observedVolumeSinks.clear()
        observedSystemVolumePlugin = null
    }
    private fun refreshVolumeSinkListeners() {
        val plugin = observedSystemVolumePlugin ?: return
        observedVolumeSinks.forEach { it.removeListener(systemVolumeUpdated) }
        observedVolumeSinks.clear()
        plugin.sinks.forEach { it.addListener(systemVolumeUpdated); observedVolumeSinks.add(it) }
    }
    private fun notice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (ready) web.evaluateJavascript("msg(${JSONObject.quote(message)})", null)
    }
    private fun isShutdownCommand(name: String): Boolean {
        val normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        return normalized.contains("deslig") || normalized.contains("shutdown") || normalized.contains("power off")
    }
    private fun screenBrightnessLevel(name: String, command: String): Int? {
        val match = Regex("^(?:/usr/bin/)?brightnessctl -d nvidia_wmi_ec_backlight set ([0-9]{1,3})%$").matchEntire(command)
            ?: return null
        val level = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 && it % 10 == 0 }
            ?: return null
        return level.takeIf { name == "Brilho da tela $it%" }
    }
    private fun keyboardBrightnessLevel(name: String, command: String): Int? {
        val brightnessctl = Regex("^(?:/usr/bin/)?brightnessctl -d ['\"]?asus::kbd_backlight['\"]? set ([0-3])$").matchEntire(command)
        if (brightnessctl != null) {
            val level = brightnessctl.groupValues[1].toIntOrNull() ?: return null
            return level.takeIf { name == "Brilho do teclado $it" }
        }
        val match = Regex("^(?:/usr/bin/)?asusctl leds set (off|low|med|high)$").matchEntire(command)
            ?: return null
        return when (match.groupValues[1]) { "off" -> 0; "low" -> 1; "med" -> 2; else -> 3 }
    }
    private fun lightingPreferenceKey(deviceId: String, field: String) = "lighting:$deviceId:$field"
    private fun rememberLightingRequest(deviceId: String, command: CommandInfo) {
        screenBrightnessLevel(command.name, command.command)?.let { level ->
            prefs.edit().putInt(lightingPreferenceKey(deviceId, "screenLevel"), level).apply()
        }
        keyboardBrightnessLevel(command.name, command.command)?.let { level ->
            prefs.edit().putInt(lightingPreferenceKey(deviceId, "keyboardLevel"), level).apply()
        }
    }
    private fun runAnnouncedCommand(deviceId: String, key: String): Boolean {
        val d = kde.getDevice(deviceId)?.takeIf { it.isPaired && it.isReachable }
            ?: return notice("Notebook desconectado. Comando não enviado.").let { false }
        val plugin = d.getPlugin<RunCommandPlugin>()
            ?: return notice("Comandos remotos indisponíveis.").let { false }
        val command = plugin.commandList.firstOrNull { it.key == key }
            ?: return notice("Este comando não está mais anunciado pelo notebook.").let { false }
        rememberLightingRequest(deviceId, command)
        plugin.runCommand(command.key)
        commandForwarded = true
        return true
    }
    private fun publish() {
        if (!::web.isInitialized) return
        val ds = kde.devices.values.toList().sortedBy { it.name }
        val chosen = ds.firstOrNull { it.deviceId == selected }
        // Keep a valid saved choice while it is offline. Only choose a new default
        // when the saved device has actually been removed/unpaired.
        if ((selected.isEmpty() || chosen?.isPaired == false) && chosen == null) {
            ds.firstOrNull { it.isPaired }?.let {
                selected = it.deviceId
                prefs.edit().putString("device", selected).apply()
            }
        }
        val d = device()
        for (v in ds) {
            val connected = v.isPaired && v.isReachable
            val before = priorConnection.put(v.deviceId, connected)
            if (before != null && before != connected) {
                NexoStore.event(this, "${v.name} · ${if (connected) "Conectado" else "Desconectado"}")
                if (connected) runConnectionRoutines(v)
            }
        }
        val live = online()
        val runCommand = live?.getPlugin<RunCommandPlugin>()
        if (runCommand !== observedRunCommandPlugin) {
            observedRunCommandPlugin?.removeCommandsUpdatedCallback(runCommandsChanged)
            observedRunCommandPlugin = runCommand
            runCommand?.addCommandsUpdatedCallback(runCommandsChanged)
        }
        val mp = live?.getPlugin<MousePadPlugin>()
        val music = live?.getPlugin<MprisPlugin>()
        val systemVolume = live?.getPlugin<SystemVolumePlugin>()
        if (systemVolume !== observedSystemVolumePlugin) {
            detachSystemVolume()
            observedSystemVolumePlugin = systemVolume
            systemVolume?.addSinkListener(systemVolumeSinksChanged)
            systemVolume?.requestSinkList()
        }
        refreshVolumeSinkListeners()
        if (music !== observedMprisPlugin) {
            observedMprisPlugin?.removePlayerStatusUpdatedHandler("nexo")
            observedMprisPlugin?.removePlayerListUpdatedHandler("nexo")
            observedMprisPlugin = music
            sessionMediaSelection = ""
            music?.setPlayerStatusUpdatedHandler("nexo", mprisStatusChanged)
            music?.setPlayerListUpdatedHandler("nexo", mprisPlayersChanged)
        }
        val players = JSONArray()
        music?.playerList?.forEach { n -> music.getPlayerStatus(n)?.let { p ->
            players.put(JSONObject().put("name", n).put("title", p.title).put("artist", p.artist).put("playing", p.isPlaying)
                .put("volume", if (p.isSetVolumeAllowed) p.volume else JSONObject.NULL).put("volumeKnown", p.isSetVolumeAllowed)
                .put("play", p.isPlayAllowed).put("pause", p.isPauseAllowed)
                .put("next", p.isGoNextAllowed).put("previous", p.isGoPreviousAllowed).put("setVolume", p.isSetVolumeAllowed))
        } }
        val storedPlayer = if (selected.isNotEmpty()) prefs.getString("mprisPlayer:$selected", "") ?: "" else ""
        val resolvedPlayer = music?.playerList?.takeIf { it.isNotEmpty() }
            ?.let { MprisPlayerSelection.resolve(storedPlayer, it) }
        if (resolvedPlayer != null && resolvedPlayer != storedPlayer) {
            prefs.edit().putString("mprisPlayer:$selected", resolvedPlayer).apply()
        }
        val savedPlayer = resolvedPlayer ?: storedPlayer
        if (resolvedPlayer != null) {
            val selection = "$selected:$resolvedPlayer"
            if (selection != sessionMediaSelection) {
                music.getPlayerStatus(resolvedPlayer)?.let { MprisMediaSession.instance.playerSelected(it) }
                sessionMediaSelection = selection
            }
        } else sessionMediaSelection = ""
        val defaultSink = systemVolume?.sinks?.firstOrNull { it.isDefault }
        val systemVolumeState = JSONObject()
            .put("available", defaultSink != null && defaultSink.maxVolume > 0)
            .put("sink", defaultSink?.name ?: JSONObject.NULL)
            .put("volume", if (defaultSink != null && defaultSink.maxVolume > 0) (defaultSink.volume * 100.0 / defaultSink.maxVolume).roundToInt().coerceIn(0, 100) else JSONObject.NULL)
        val commands = JSONArray()
        runCommand?.commandList?.forEach { command ->
            val item = JSONObject().put("key", command.key).put("name", command.name)
            screenBrightnessLevel(command.name, command.command)?.let { item.put("screenLevel", it) }
            keyboardBrightnessLevel(command.name, command.command)?.let { item.put("keyboardLevel", it) }
            commands.put(item)
        }
        val savedScreenLevel = if (selected.isNotEmpty()) prefs.getInt(lightingPreferenceKey(selected, "screenLevel"), -1) else -1
        val savedKeyboardLevel = if (selected.isNotEmpty()) prefs.getInt(lightingPreferenceKey(selected, "keyboardLevel"), -1) else -1
        val requestedLighting = JSONObject()
            .put("screenLevel", if (savedScreenLevel >= 0) savedScreenLevel else JSONObject.NULL)
            .put("keyboardLevel", if (savedKeyboardLevel >= 0) savedKeyboardLevel else JSONObject.NULL)
        val state = JSONObject().put("devices", JSONArray(ds.map { v -> JSONObject().put("id", v.deviceId).put("name", v.name)
            .put("paired", v.isPaired).put("reachable", v.isReachable).put("pairStatus", v.pairStatus.name)
            .put("verification", v.verificationKey ?: "").put("protocol", v.protocolVersion) }))
            .put("selected", selected).put("name", d?.name ?: "Meu notebook").put("connected", live != null)
            .put("mouse", mp != null).put("keyboard", mp?.isKeyboardEnabled == true)
            .put("share", live?.getPlugin<SharePlugin>() != null).put("presenter", live?.getPlugin<PresenterPlugin>() != null)
            .put("runcommand", runCommand != null)
            .put("players", players).put("selectedPlayer", savedPlayer).put("systemVolume", systemVolumeState).put("commands", commands).put("lightingRequested", requestedLighting).put("history", NexoStore.history(this))
            .put("transfers", NexoStore.transfers(this)).put("routines", JSONArray(prefs.getString("routines", "[]")))
            .put("theme", prefs.getString("theme", "Sistema")).put("shortcuts", JSONArray(prefs.getString("shortcuts", "[0,1,2]")))
            .put("dark", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("reduced", prefs.getBoolean("reduced", false)).toString()
        if (ready && state != lastState) { lastState = state; web.evaluateJavascript("update($state)", null) }
    }
    private fun runConnectionRoutines(d: Device) {
        val rs = JSONArray(prefs.getString("routines", "[]"))
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            if (r.optBoolean("enabled") && r.optString("device") == d.deviceId && r.optString("trigger") == "Ao conectar") executeRoutine(r, d)
        }
    }
    private fun executeRoutine(r: JSONObject, d: Device) {
        if (!d.isReachable || !d.isPaired) { notice("Rotina indisponível: notebook desconectado."); return }
        // Only a safe built-in action is automatic. Power and arbitrary shell commands are never automated.
        val p = d.getPlugin<MprisPlugin>()
        if (r.optString("action") == "Pausar mídia" && p != null && p.playerList.isNotEmpty()) {
            p.playerList.forEach { p.getPlayerStatus(it)?.takeIf { v -> v.isPauseAllowed }?.sendPause() }
            NexoStore.event(this, "Rotina ${r.optString("name")} · solicitação de pausa enviada")
        } else notice("Rotina indisponível: nenhum reprodutor com suporte a pausa.")
    }
    inner class Bridge {
        @JavascriptInterface fun action(name: String, data: String) {
            val bridgeReceivedAt = SystemClock.elapsedRealtimeNanos()
            handler.post {
                val dispatchStartedAt = SystemClock.elapsedRealtimeNanos()
                var dispatchCompletedAt = dispatchStartedAt
                commandForwarded = false
                try { dispatch(name, JSONObject(data)); dispatchCompletedAt = SystemClock.elapsedRealtimeNanos(); publish() }
                catch (e: Exception) { dispatchCompletedAt = SystemClock.elapsedRealtimeNanos(); notice("Não foi possível executar: ${e.localizedMessage ?: "recurso indisponível"}") }
                val finishedAt = SystemClock.elapsedRealtimeNanos()
                if (name in setOf("command", "media", "systemVolume", "present", "text")) {
                    Log.d("NexoLatency", "action=$name mainQueueMs=${(dispatchStartedAt - bridgeReceivedAt) / 1_000_000.0} pluginDispatchMs=${(dispatchCompletedAt - dispatchStartedAt) / 1_000_000.0} publishMs=${(finishedAt - dispatchCompletedAt) / 1_000_000.0} forwarded=$commandForwarded remoteExecution=unconfirmed")
                }
            }
        }
    }
    private fun dispatch(action: String, a: JSONObject) {
        when (action) {
            "refresh" -> { BackgroundService.ForceRefreshConnections(this); notice("Buscando dispositivos na rede…") }
            "select" -> { selected = a.getString("id"); prefs.edit().putString("device", selected).apply() }
            "selectPlayer" -> {
                val music = online()?.getPlugin<MprisPlugin>() ?: return notice("Reprodutor indisponível.")
                val playerName = a.optString("player")
                if (playerName.isNotEmpty() && !music.playerList.contains(playerName)) return notice("Este reprodutor não está mais disponível.")
                prefs.edit().putString("mprisPlayer:$selected", playerName).apply()
                sessionMediaSelection = if (playerName.isEmpty()) "" else "$selected:$playerName"
                MprisMediaSession.instance.playerSelected(music.getPlayerStatus(playerName))
            }
            "pair" -> kde.getDevice(a.getString("id"))?.let { d ->
                if (!d.isReachable) return notice("Dispositivo fora da rede.")
                if (d.pairStatus == PairingHandler.PairState.RequestedByPeer) {
                    AlertDialog.Builder(this).setTitle("Confirmar pareamento com ${d.name}?")
                        .setMessage("Compare a chave nos dois aparelhos:\n${d.verificationKey ?: "Chave ainda indisponível"}")
                        .setNegativeButton("Cancelar", null).setPositiveButton("Aceitar") { _, _ -> d.acceptPairing() }.show()
                } else { d.requestPairing(); notice("Solicitação enviada. Confirme no notebook e compare a chave.") }
            }
            "disconnect" -> online()?.disconnect()
            "unpair" -> device()?.let { d -> AlertDialog.Builder(this).setTitle("Remover pareamento?").setMessage(d.name)
                .setNegativeButton("Cancelar", null).setPositiveButton("Remover") { _, _ -> d.unpair() }.show() }
            "sendFiles" -> { if (online()?.getPlugin<SharePlugin>() == null) return notice("Compartilhamento indisponível."); retryName = null; fileTarget = selected; picker.launch(arrayOf("*/*")) }
            "retryTransfer" -> {
                val transfer = NexoStore.transfers(this).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == a.optString("id") } }
                    ?: return notice("Transferência não encontrada.")
                val d = kde.getDevice(transfer.optString("device"))
                val plugin = d?.takeIf { it.isPaired && it.isReachable }?.getPlugin<SharePlugin>()
                    ?: return notice("Notebook desconectado ou compartilhamento indisponível.")
                val name = transfer.optString("name")
                val uri = NexoStore.retryUri(this, d.deviceId, name)?.let(Uri::parse)
                if (uri == null || checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    fileTarget = d.deviceId; retryName = name; picker.launch(arrayOf("*/*"))
                    return notice("A permissão do arquivo expirou. Selecione novamente: $name")
                }
                io.execute {
                    try { plugin.sendFiles(listOf(uri)) }
                    catch (e: Exception) { runOnUiThread { notice("Não foi possível iniciar uma nova tentativa.") } }
                }
            }
            "openTransfer" -> {
                val transfer = NexoStore.transfers(this).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == a.optString("id") } }
                    ?: return notice("Transferência não encontrada.")
                if (transfer.optString("direction") != "Recebido" || transfer.optString("status") != "Sucesso reportado pelo KDE Connect") {
                    return notice("Este arquivo ainda não está disponível para abrir.")
                }
                val savedUri = transfer.optString("fileUri").takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: return notice("URI do arquivo recebido indisponível.")
                val uri = if (savedUri.scheme == "file" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val path = savedUri.path ?: return notice("Caminho do arquivo indisponível.")
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(path))
                } else savedUri
                val mime = contentResolver.getType(uri) ?: "*/*"
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(transfer.optString("name"), uri)
                }
                if (view.resolveActivity(packageManager) == null) return notice("Não há aplicativo para abrir este arquivo.")
                startActivity(Intent.createChooser(view, "Abrir arquivo"))
            }
            "cancelTransfer" -> ShareJob.cancel(a.getLong("job"))
            "text" -> { val p = online()?.getPlugin<MousePadPlugin>(); if (p == null || !p.isKeyboardEnabled) return notice("Teclado remoto indisponível."); p.sendText(a.getString("text").take(20000)); commandForwarded = true; NexoStore.event(this, "Texto solicitado ao teclado remoto; sem confirmação de digitação") }
            "mouse" -> { val d = online() ?: return notice("Notebook desconectado; entrada remota indisponível."); val p = d.getPlugin<MousePadPlugin>() ?: return notice("Plugin Mousepad indisponível no notebook."); when (a.getString("kind")) {
                "move" -> p.sendMouseDelta(a.optDouble("dx").toFloat().coerceIn(-500f,500f), a.optDouble("dy").toFloat().coerceIn(-500f,500f))
                "scroll" -> p.sendScroll(a.optDouble("dx").coerceIn(-500.0,500.0), a.optDouble("dy").coerceIn(-500.0,500.0))
                "left" -> p.sendLeftClick(); "right" -> p.sendRightClick(); "double" -> p.sendDoubleClick(); "hold" -> p.sendSingleHold(); "release" -> p.sendSingleRelease()
            } }
            "media" -> {
                val music = online()?.getPlugin<MprisPlugin>() ?: return notice("Reprodutor indisponível.")
                val storedPlayer = prefs.getString("mprisPlayer:$selected", "") ?: ""
                val p = MprisPlayerSelection.resolvePlayer(music, storedPlayer)
                    ?: return notice("Nenhum reprodutor disponível.")
                val playerId = p.playerName
                if (playerId != storedPlayer) prefs.edit().putString("mprisPlayer:$selected", playerId).apply()
                val sessionSelection = "$selected:${p.playerName}"
                if (sessionSelection != sessionMediaSelection) {
                    MprisMediaSession.instance.playerSelected(p)
                    sessionMediaSelection = sessionSelection
                }
                when(a.getString("kind")) {
                "toggle" -> if (p.isPlayAllowed || p.isPauseAllowed) { Log.d("NexoMpris", "player=${p.playerName} toggle playing=${p.isPlaying} canPlay=${p.isPlayAllowed} canPause=${p.isPauseAllowed}"); p.sendPlayPause(); commandForwarded = true } else return notice("Reprodução indisponível neste reprodutor.")
                "play" -> if(p.isPlayAllowed) { p.sendPlay(); commandForwarded = true } else return notice("Reprodução indisponível neste reprodutor.")
                "pause" -> if(p.isPauseAllowed) { p.sendPause(); commandForwarded = true } else return notice("Pausa indisponível neste reprodutor.")
                "next" -> if(p.isGoNextAllowed) { p.sendNext(); commandForwarded = true } else return notice("Próxima faixa indisponível.")
                "previous" -> if(p.isGoPreviousAllowed) { p.sendPrevious(); commandForwarded = true } else return notice("Faixa anterior indisponível.")
                "volume" -> if(p.isSetVolumeAllowed) { p.sendSetVolume(a.getInt("value").coerceIn(0,100)); commandForwarded = true } else return notice("Volume indisponível neste reprodutor.")
            } }
            "systemVolume" -> {
                val plugin = online()?.getPlugin<SystemVolumePlugin>() ?: return notice("Volume do notebook indisponível.")
                val sink = plugin.sinks.firstOrNull { it.name == a.optString("sink") }
                    ?: return notice("Dispositivo de áudio indisponível.")
                if (sink.maxVolume <= 0) return notice("Volume do notebook indisponível.")
                val percent = a.getInt("value").coerceIn(0, 100)
                val volume = ((percent * sink.maxVolume) / 100.0).roundToInt().coerceIn(0, sink.maxVolume)
                Log.d("NexoSystemVolume", "sink=${sink.name} requestedPercent=$percent remoteValue=$volume max=${sink.maxVolume}")
                plugin.sendVolume(sink.name, volume)
                commandForwarded = true
            }
            "present" -> { val p = online()?.getPlugin<PresenterPlugin>() ?: return notice("Apresentação indisponível."); when(a.getString("kind")) { "next" -> p.sendNext(); "previous" -> p.sendPrevious(); "start" -> p.sendFullscreen(); "stop" -> p.sendEsc(); else -> return }; commandForwarded = true }
            "command" -> {
                val d = online() ?: return notice("Notebook desconectado.")
                val p = d.getPlugin<RunCommandPlugin>() ?: return notice("Comandos remotos indisponíveis.")
                val c = p.commandList.firstOrNull { it.key == a.getString("key") }
                    ?: return notice("Este comando não está mais anunciado pelo notebook.")
                if (!isShutdownCommand(c.name)) commandForwarded = runAnnouncedCommand(d.deviceId, c.key)
                else AlertDialog.Builder(this).setTitle("Desligar ${d.name}?")
                    .setMessage("O comando remoto “${c.name}” pode encerrar trabalhos abertos.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desligar") { _, _ ->
                        commandForwarded = runAnnouncedCommand(d.deviceId, c.key)
                    }.show()
            }
            "theme" -> prefs.edit().putString("theme", a.getString("value").takeIf { it in listOf("Sistema","Claro","Escuro") } ?: "Sistema").apply()
            "reduced" -> prefs.edit().putBoolean("reduced", a.getBoolean("value")).apply()
            "shortcuts" -> { val list = a.getJSONArray("value"); require(list.length()==3); for(i in 0..2) require(list.getInt(i) in 0..3); prefs.edit().putString("shortcuts", list.toString()).apply() }
            "saveRoutine" -> { val rs = JSONArray(prefs.getString("routines", "[]")); require(rs.length()<50); rs.put(JSONObject().put("name",a.getString("name").take(45)).put("device",selected).put("trigger",a.getString("trigger").take(30)).put("action","Pausar mídia").put("enabled",true)); prefs.edit().putString("routines",rs.toString()).apply() }
            "toggleRoutine", "deleteRoutine", "runRoutine" -> { val rs = JSONArray(prefs.getString("routines", "[]")); val i = a.getInt("index"); val r = rs.getJSONObject(i)
                if(action=="runRoutine") { val d = kde.getDevice(r.optString("device")) ?: return notice("Dispositivo da rotina indisponível."); executeRoutine(r,d) }
                else if(action=="deleteRoutine") AlertDialog.Builder(this).setTitle("Excluir rotina?")
                    .setMessage(r.optString("name")).setNegativeButton("Cancelar",null)
                    .setPositiveButton("Excluir") { _, _ -> rs.remove(i); prefs.edit().putString("routines",rs.toString()).apply(); publish() }.show()
                else { r.put("enabled",!r.optBoolean("enabled")); prefs.edit().putString("routines",rs.toString()).apply() }
            }
            "permissions" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            "native" -> startActivity(Intent(this, MainActivity::class.java))
            "licenses" -> { val text = resources.openRawResource(org.kde.kdeconnect_tp.R.raw.license).bufferedReader().use { it.readText() }; AlertDialog.Builder(this).setTitle("Nexo · KDE Connect e licenças").setMessage(text).setPositiveButton("Fechar",null).show() }
        }
    }
}
