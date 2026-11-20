/*
 * SPDX-FileCopyrightText: 2019 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.os.Handler
import android.os.Looper
import androidx.annotation.GuardedBy
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.share.SharePlugin.Companion.KEY_NUMBER_OF_FILES
import org.kde.kdeconnect.plugins.share.SharePlugin.Companion.KEY_TOTAL_PAYLOAD_SIZE
import org.kde.kdeconnect_tp.R
import java.util.IdentityHashMap
import java.util.UUID

/**
 * A [ShareJob] that sends Files to another device.
 *
 * We represent the individual upload requests as [NetworkPacket]s.
 *
 * Each packet should have a 'filename' property and a payload. If the payload is
 * missing, we'll just send an empty file. You can add new packets anytime via
 * [addNetworkPackets].
 *
 * The I/O-part of this file sending is handled by
 * [Device.sendPacketBlocking].
 *
 * @see CompositeReceiveFileJob
 * @see Device.SendPacketStatusCallback
 */
class CompositeUploadFileJob(device: Device, onFinished: (ShareJob) -> Unit)
    : ShareJob(device, onFinished)
{

    @Volatile
    private var isRunning: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentFileName: String = ""
    private var currentFileNum: Int = 0
    @Volatile private var currentNexoId: String = ""
    @Volatile private var currentFileProgress: Int = 0
    @Volatile private var currentFileProgressKnown: Boolean = false
    @Volatile private var currentFileComplete: Boolean = false
    private var updatePacketPending: Boolean = false
    private var totalSend: Long = 0
    private var prevProgressPercentage: Int = 0
    private val uploadNotification: UploadNotification = UploadNotification(device, id)

    private val lock = Any() // Use to protect concurrent access to the variables below

    @GuardedBy("lock")
    private val networkPacketList = mutableListOf<NetworkPacket>()

    @GuardedBy("lock")
    private val transferIds = IdentityHashMap<NetworkPacket, String>()

    private var currentNetworkPacket: NetworkPacket? = null
    private val sendPacketStatusCallback: SendPacketStatusCallback = SendPacketStatusCallback()

    @GuardedBy("lock")
    private var totalNumFiles: Int = 0

    @GuardedBy("lock")
    private var totalPayloadSize: Long = 0

    // Once set, this job can't be used anymore and addNetworkPackets() will reject new packets
    @GuardedBy("lock")
    private var finished: Boolean = false

    override fun run() {
        isRunning = true

        try {
            while (!isCancelled) {
                val packet = synchronized(lock) {
                    val next = networkPacketList.removeFirstOrNull()
                    if (next == null) {
                        finished = true
                        break
                    }
                    currentNetworkPacket = next
                    currentNexoId = transferIds[next] ?: UUID.randomUUID().toString()
                    currentFileProgress = 0
                    currentFileProgressKnown = false
                    currentFileComplete = false
                    next
                }

                currentFileName = packet.getString("filename", "Arquivo")
                currentFileNum++

                nexoTransfer(currentFileProgress, "Enviando", false)
                setProgress(prevProgressPercentage)

                addTotalsToNetworkPacket(packet)

                // We set sendPayloadFromSameThread to true so this call blocks until the payload
                // has been received by the other end, so payloads are sent one by one.
                if (!device.sendPacketBlocking(packet, sendPacketStatusCallback, true)) {
                    throw RuntimeException("Sending packet failed")
                }
                currentFileComplete = true
                nexoTransfer(currentFileProgress, "Sucesso reportado pelo KDE Connect", currentFileProgressKnown)
            }

            if (isCancelled) {
                if (currentNexoId.isNotEmpty() && !currentFileComplete) nexoTransfer(currentFileProgress, "Cancelado", currentFileProgressKnown)
                markQueuedInterrupted(cancelFirst = currentNexoId.isEmpty())
                uploadNotification.cancel()
            } else {
                uploadNotification.setFinished(
                    device.context.resources.getQuantityString(
                        R.plurals.sent_files_title,
                        currentFileNum,
                        device.name,
                        currentFileNum,
                    ),
                )
                uploadNotification.show()
            }
        } catch (e: RuntimeException) {
            val status = when { isCancelled -> "Cancelado"; !device.isReachable -> "Interrompido"; else -> "Falhou" }
            if (currentNexoId.isNotEmpty() && !currentFileComplete) nexoTransfer(currentFileProgress, status, currentFileProgressKnown)
            markQueuedInterrupted(cancelFirst = isCancelled && currentNexoId.isEmpty())
            if (isCancelled) uploadNotification.cancel() else {
                synchronized(lock) {
                    val failedFiles = totalNumFiles - currentFileNum + 1
                    uploadNotification.setFailed(
                        device.context.resources.getQuantityString(
                            R.plurals.send_files_fail_title,
                            failedFiles,
                            device.name,
                            failedFiles,
                            totalNumFiles,
                        ),
                    )
                }
                uploadNotification.show()
            }
        } finally {
            isRunning = false

            synchronized(lock) {
                finished = true
                for (networkPacket in networkPacketList) {
                    networkPacket.payload?.close()
                }
                networkPacketList.clear()
            }
        }
    }

    private fun addTotalsToNetworkPacket(networkPacket: NetworkPacket) {
        synchronized(lock) {
            networkPacket[KEY_NUMBER_OF_FILES] = totalNumFiles
            networkPacket[KEY_TOTAL_PAYLOAD_SIZE] = totalPayloadSize
        }
    }

    private fun nexoTransfer(progress: Int, status: String, progressKnown: Boolean? = null) {
        if (currentNexoId.isNotEmpty()) org.kde.kdeconnect.nexo.NexoStore.transfer(
            device, currentNexoId, currentFileName, "Enviado", progress, status, progressKnown
        )
    }

    private fun markQueuedInterrupted(cancelFirst: Boolean = false) {
        synchronized(lock) {
            for ((index, packet) in networkPacketList.withIndex()) {
                val transferId = transferIds[packet] ?: continue
                org.kde.kdeconnect.nexo.NexoStore.transfer(
                    device, transferId, packet.getString("filename", "Arquivo"), "Enviado", 0,
                    if (cancelFirst && index == 0) "Cancelado" else "Interrompido", false
                )
            }
        }
    }

    private fun setProgress(progress: Int) {
        synchronized(lock) {
            uploadNotification.setProgress(
                progress,
                device.context.resources.getQuantityString(
                    R.plurals.outgoing_files_text,
                    totalNumFiles,
                    currentFileName,
                    currentFileNum,
                    totalNumFiles,
                ),
            )
        }
        uploadNotification.show()
    }

    /**
     * Adds either all the packets or none of them.
     *
     * @return false if the job has already finished and won't send these packets, so the caller
     * should use a new job instead
     */
    fun addNetworkPackets(networkPackets: List<NetworkPacket>): Boolean {
        synchronized(lock) {
            if (finished || isCancelled) {
                return false
            }

            for (networkPacket in networkPackets) {
                networkPacketList.add(networkPacket)
                val transferId = UUID.randomUUID().toString()
                transferIds[networkPacket] = transferId
                org.kde.kdeconnect.nexo.NexoStore.transfer(
                    device, transferId, networkPacket.getString("filename", "Arquivo"), "Enviado", 0, "Preparando", false
                )

                totalNumFiles++

                if (networkPacket.payloadSize >= 0) {
                    totalPayloadSize += networkPacket.payloadSize
                }
            }

            uploadNotification.setTitle(
                device.context.resources.getQuantityString(
                    R.plurals.outgoing_file_title,
                    totalNumFiles,
                    totalNumFiles,
                    device.name,
                ),
            )

            // If we are already sending a file, update the notification right away to show the new number of files
            if (isRunning && currentFileNum > 0) {
                setProgress(prevProgressPercentage)
            }

            // Give SharePlugin some time to add more NetworkPackets
            if (isRunning && !updatePacketPending) {
                updatePacketPending = true
                handler.post { sendUpdatePacket() }
            }
        }
        return true
    }

    /**
     * Use this to send metadata ahead of all the other [networkPacketList] packets.
     */
    private fun sendUpdatePacket() {
        val np = NetworkPacket(SharePlugin.PACKET_TYPE_SHARE_REQUEST_UPDATE)

        synchronized(lock) {
            np[KEY_NUMBER_OF_FILES] = totalNumFiles
            np[KEY_TOTAL_PAYLOAD_SIZE] = totalPayloadSize
            updatePacketPending = false
        }

        device.sendPacket(np)
    }

    override fun cancel() {
        super.cancel()

        currentNetworkPacket?.cancel()
    }

    private inner class SendPacketStatusCallback : Device.SendPacketStatusCallback() {
        override fun onPayloadProgressChanged(percent: Int) {
            val payloadSize = currentNetworkPacket?.payloadSize ?: return
            val progress = if (totalPayloadSize > 0) {
                ((totalSend.toDouble() + payloadSize.toDouble() * percent.coerceIn(0, 100) / 100.0) * 100.0 / totalPayloadSize).toInt().coerceIn(0, 100)
            } else percent.coerceIn(0, 100)
            currentFileProgress = percent.coerceIn(0, 100)
            currentFileProgressKnown = true
            nexoTransfer(currentFileProgress, "Enviando", true)

            if (progress != prevProgressPercentage) {
                setProgress(progress)
                prevProgressPercentage = progress
            }
        }

        override fun onSuccess() {
            val payloadSize = currentNetworkPacket?.payloadSize ?: return
            if (payloadSize == 0L) {
                synchronized(lock) {
                    if (networkPacketList.isEmpty()) {
                        setProgress(100)
                    }
                }
            } else {
                totalSend += payloadSize
            }
        }

        override fun onFailure(e: Throwable) {
            // Handled in the run() function when sendPacketBlocking returns false
        }
    }
}
