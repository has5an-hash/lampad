package app.lampad.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkController(
    private var host: String,
    private val port: Int = 47891
) {
    interface Listener {
        fun onCommand(command: String)
        fun onConnectionError(message: String)
    }

    private val receiveExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val socketReady = CountDownLatch(1)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var listener: Listener? = null

    fun setHost(value: String) {
        host = value.trim()
    }

    fun start(listener: Listener) {
        this.listener = listener

        if (running) {
            send("HELLO")
            return
        }

        running = true

        receiveExecutor.execute {
            try {
                val newSocket = DatagramSocket()
                newSocket.reuseAddress = true
                socket = newSocket
                socketReady.countDown()

                send("HELLO")

                val buffer = ByteArray(4096)

                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    newSocket.receive(packet)

                    val command = String(
                        packet.data,
                        packet.offset,
                        packet.length,
                        Charsets.UTF_8
                    )

                    this.listener?.onCommand(command)
                }
            } catch (e: SocketException) {
                socketReady.countDown()
                if (running) {
                    this.listener?.onConnectionError(
                        e.message ?: "خطای ساخت اتصال شبکه"
                    )
                }
            } catch (t: Throwable) {
                socketReady.countDown()
                if (running) {
                    this.listener?.onConnectionError(
                        t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun send(message: String) {
        val destination = host.trim()
        if (destination.isBlank() || !running) return

        sendExecutor.execute {
            try {
                if (!socketReady.await(2, TimeUnit.SECONDS)) return@execute

                val activeSocket = socket ?: return@execute
                if (activeSocket.isClosed) return@execute

                val bytes = message.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(destination),
                    port
                )

                activeSocket.send(packet)
            } catch (t: Throwable) {
                if (running) {
                    listener?.onConnectionError(
                        t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun close() {
        running = false

        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        socket = null
        listener = null

        receiveExecutor.shutdownNow()
        sendExecutor.shutdownNow()
    }
}
