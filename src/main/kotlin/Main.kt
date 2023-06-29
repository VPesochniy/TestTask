import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Heartbeat
import io.dronefleet.mavlink.common.LocalPositionNed
import io.dronefleet.mavlink.common.MavModeFlag
import io.dronefleet.mavlink.common.RcChannelsOverride
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.*
import java.util.*
import javax.imageio.ImageIO


var imageJob: Job? = null
var server: HttpServer? = null
var serverSocket: ServerSocket? = null

var data = arrayOfNulls<ByteArray>(4)
var rcChannels = intArrayOf(1500, 1500, 1500, 1500)

fun downloadPictures() {
    println("downloading pictures")
    for (i in intArrayOf(1, 2, 3, 4)) {
        val url = URL("https://raw.githubusercontent.com/PeterBozhko/AndroidTest/main/copter$i.png")
        val image = ImageIO.read(url)
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        data[i - 1] = baos.toByteArray()
    }
    println("pictures downloaded")
}

fun main(args: Array<String>) {
    try {
        if (data[0] == null) downloadPictures()
        server = HttpServer.create(InetSocketAddress(8080), 0)
        server!!.createContext("/info", InfoHandler())
        server!!.start()
        serverSocket = ServerSocket(8888)
        println("server run")
        val client = serverSocket!!.accept()
        println("new client = ${client.inetAddress}")
        imageSocket(InetSocketAddress(client.inetAddress, client.port))
        val control = Control(InetSocketAddress(client.inetAddress, client.port))
        while (!control.isClose()) {
        }

    } catch (err: Exception) {
        println(err)
    } finally {
        println("client out")
        if (imageJob != null) imageJob?.cancel()
        if (server != null) server?.stop(0)
        if (serverSocket != null) serverSocket?.close()
        main(emptyArray())
    }
}

private fun imageSocket(address: InetSocketAddress) {
    imageJob = CoroutineScope(Dispatchers.IO).launch {
        val socket = DatagramSocket()
        var lastPicTime = System.currentTimeMillis()
        var fps: Long
        var img = 0
        var packet: DatagramPacket
        while (!socket.isClosed) {
            fps = 2000 - rcChannels[0].toLong()
            if (System.currentTimeMillis() - lastPicTime > fps) {
                lastPicTime = System.currentTimeMillis()
                img++
            }
            if (img == 4) img = 0
            packet = DatagramPacket(data[img], data[img]!!.size, address)
            socket.send(packet)
            delay(40L)
        }
    }
}

class Control(address: InetSocketAddress) {
    private var position = floatArrayOf(0f, 0f, 0f)
    private var lastMes: Long = System.currentTimeMillis()
    private var controlSocket: DatagramSocket? = null
    private var address: InetSocketAddress? = null
    private val buff = ByteArray(512)
    private val mavInStream: PipedInputStream = PipedInputStream(1024)
    private val pOutStream = PipedOutputStream(mavInStream)

    private inner class MavOutStream : OutputStream() {
        private var buffer: ByteArray = ByteArray(512)
        override fun flush() {
            controlSocket!!.send(DatagramPacket(buffer, 0, buffer.size, address))
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray) {
            buffer = b
            flush()
        }
    }

    private var connection: MavlinkConnection? = null

    init {
        try {
            controlSocket = DatagramSocket(8001)
            println(address)
            controlSocket!!.soTimeout = 5000

            this.address = address
            connection = MavlinkConnection.create(mavInStream, MavOutStream())
            inputThread()
            readThread()
            outHeartBeatThread()
            outPositionThread()
        } catch (e: SocketException) {
            error(e)
        } catch (e: SecurityException) {
            error(e)
        }

    }

    fun isClose(): Boolean {
        if (controlSocket == null) return true
        return controlSocket!!.isClosed
    }

    private fun inputThread() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (!controlSocket!!.isClosed) {
                    val packet = DatagramPacket(buff, buff.size)
                    controlSocket!!.receive(packet)
                    address = InetSocketAddress(address!!.address, packet.port)
                    pOutStream.write(Arrays.copyOfRange(packet.data, 0, packet.length))
                }
            } catch (e: SocketTimeoutException) {
                println(e)
            } finally {
                controlSocket!!.close()
            }
        }
    }

    private fun readThread() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    if (System.currentTimeMillis() - lastMes > 5000) {
                        controlSocket!!.close()
                        return@launch
                    }
                    if (mavInStream.available() < 50) {
                        delay(20)
                        continue
                    }

                    val payload = connection!!.next().payload
                    println(payload.toString())
                    when (payload) {
                        is Heartbeat -> {
                            lastMes = System.currentTimeMillis()
                        }

                        is RcChannelsOverride -> {
                            rcChannels = intArrayOf(
                                payload.chan1Raw(),
                                payload.chan2Raw(),
                                payload.chan3Raw(),
                                payload.chan4Raw()
                            )
                        }
                    }
                }
            } catch (e: SocketException) {
                error(e)
            }
        }
    }

    private fun outHeartBeatThread() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (!controlSocket!!.isClosed) {
                    val message = Heartbeat.builder()
                        .baseMode(MavModeFlag.MAV_MODE_FLAG_TEST_ENABLED)
                        .build()
                    connection!!.send2(255, 1, message)
                    delay(1000L)
                }
            } catch (e: SocketException) {
                println(e)
            }
        }
    }

    private fun outPositionThread() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (!controlSocket!!.isClosed) {
                    val message = LocalPositionNed.builder()
                        .x(position[0] + (rcChannels[1] - 1500) / 10)
                        .y(position[1] + (rcChannels[2] - 1500) / 10)
                        .z(position[2] + (rcChannels[3] - 1500) / 10)
                        .build()
                    connection!!.send2(255, 1, message)
                    delay(50L)
                }
            } catch (e: SocketException) {
                println(e)
            }
        }
    }
}


private class InfoHandler : HttpHandler {
    override fun handle(exchange: HttpExchange?) {
        val response = "[0.4.5,1.6.7747]"
        exchange?.sendResponseHeaders(200, response.length.toLong())
        val os = exchange?.responseBody
        os?.write(response.toByteArray())
        os?.close()
    }
}