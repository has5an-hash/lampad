package app.lampad.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class NetworkController(
    private var host: String,
    private val port: Int = 47891
) {
    interface Listener {
        fun onCommand(command: String)
        fun onConnectionError(message: String)
    }

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun setHost(value: String) {
        host = value.trim()
    }

    fun start(listener: Listener) {
        if (running) return
        running = true
        socket = DatagramSocket()

        executor.execute {
            val buffer = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val command = packet.data.decodeToString(0, packet.length)
                    listener.onCommand(command)
                } catch (e: Exception) {
                    if (running) listener.onConnectionError(e.message ?: "Network error")
                }
            }
        }

        send("HELLO")
    }

    fun send(message: String) {
        if (host.isBlank()) return
        executor.execute {
            try {
                val bytes = message.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(host),
                    port
                )
                socket?.send(packet)
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        running = false
        socket?.close()
        socket = null
        executor.shutdownNow()
    }
}
