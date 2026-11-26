package com.beauahill.hilltv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.SyncGate
import com.beauahill.hilltv.core.SyncPage
import com.beauahill.hilltv.core.SyncRequest
import com.beauahill.hilltv.core.SyncRequestReader
import com.beauahill.hilltv.core.SyncResponse
import com.beauahill.hilltv.core.SyncRoute
import com.beauahill.hilltv.core.SyncSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * The small web server behind Settings > Sync and the login screen's "Use
 * your phone instead". It runs only while the Sync screen is open, on this
 * TV's address on the home network, and answers a phone that scanned the QR
 * code: sign this TV in, save its settings to the phone, or load a settings
 * file into it. The twin of SyncServer.swift; the page and the rules are the
 * same, from the core.
 *
 * Nothing is hosted anywhere: the phone talks to the TV directly. The page
 * is plain http, the same as most providers' own addresses, so the login a
 * phone sends travels over the home Wi-Fi unencrypted. Browsers give the Web
 * Crypto API only to secure (https) pages, so the page can't add encryption
 * of its own.
 *
 * Bytes are read off the main thread, one coroutine per connection. Every
 * request is then routed and applied on the main thread, one at a time, so
 * guesses at the code sent in parallel can't slip past the lock, and the
 * stores (Compose state) only change there. Request lines and bodies are
 * never logged: a sign-in body holds the password.
 *
 * Owned by `AppModel`, not by a screen, because signing in rebuilds
 * everything below the Sync screen.
 */
class SyncServer(private val app: AppModel) {
    sealed interface Status {
        data object Stopped : Status
        data object Starting : Status
        data class Ready(val url: String, val address: String, val code: String) : Status
        data class Failed(val message: String) : Status
    }

    var status: Status by mutableStateOf(Status.Stopped)
        private set

    /** What the phone did, newest first, for the TV to show. */
    val activity = mutableStateListOf<String>()

    /** Too many wrong codes: requests are refused until the screen reopens. */
    var isLocked: Boolean by mutableStateOf(false)
        private set

    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private val connections = Collections.synchronizedSet(HashSet<Socket>())
    private var gate = SyncGate("")

    // MARK: Running

    fun start() {
        stop()
        val address = localAddress()
        if (address == null) {
            status = Status.Failed("This TV doesn't have an address on a network. Connect it to Wi-Fi or Ethernet and try again.")
            return
        }
        val code = SyncGate.makeCode()
        gate = SyncGate(code)
        isLocked = false
        activity.clear()
        status = Status.Starting

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.scope = scope
        scope.launch {
            val server = try {
                withContext(Dispatchers.IO) { ServerSocket().apply { bind(InetSocketAddress(0)) } }
            } catch (error: IOException) {
                status = Status.Failed("Sync couldn't start: ${error.message ?: "no port was free"}")
                return@launch
            }
            socket = server
            val base = "http://$address:${server.localPort}"
            status = Status.Ready("$base/?code=$code", base, code)
            acceptLoop(server, scope)
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        synchronized(connections) {
            for (connection in connections) {
                try {
                    connection.close()
                } catch (_: IOException) {
                }
            }
            connections.clear()
        }
        status = Status.Stopped
    }

    private suspend fun acceptLoop(server: ServerSocket, scope: CoroutineScope) {
        while (scope.isActive) {
            val connection = try {
                withContext(Dispatchers.IO) { server.accept() }
            } catch (_: IOException) {
                break // Closed by stop().
            }
            connections.add(connection)
            scope.launch { serve(connection) }
        }
    }

    private suspend fun serve(connection: Socket) {
        // A connection that never finishes its request is dropped after 30
        // seconds. Closing the socket is what ends a blocking read; a timeout
        // around it alone would wait for the read to return.
        val dropper = scope?.launch {
            delay(30_000)
            try {
                connection.close()
            } catch (_: IOException) {
            }
        }
        try {
            val result = withContext(Dispatchers.IO) { readRequest(connection) }
            val response = when (result) {
                is SyncRequestReader.Result.Done -> handle(result.request)
                is SyncRequestReader.Result.Failed ->
                    SyncResponse.message("The TV couldn't read that request.", false, result.status)
                SyncRequestReader.Result.NeedMore -> null
            }
            if (response != null) {
                withContext(Dispatchers.IO) {
                    connection.getOutputStream().apply {
                        write(response.serialized())
                        flush()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // The phone went away. Nothing to tell anyone.
        } finally {
            dropper?.cancel()
            // Directly, not in a coroutine: this also runs when stop() cancels.
            connections.remove(connection)
            try {
                connection.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Blocking. Returns NeedMore if the phone hung up before the request was whole. */
    private fun readRequest(connection: Socket): SyncRequestReader.Result {
        connection.soTimeout = 15_000
        val input = connection.getInputStream()
        val reader = SyncRequestReader()
        val chunk = ByteArray(65_536)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) return SyncRequestReader.Result.NeedMore
            if (count == 0) continue
            val result = reader.add(chunk.copyOf(count))
            if (result != SyncRequestReader.Result.NeedMore) return result
        }
    }

    private fun note(text: String) {
        activity.add(0, text)
        while (activity.size > 5) activity.removeAt(activity.lastIndex)
    }

    // MARK: Requests (main thread)

    private suspend fun handle(request: SyncRequest): SyncResponse {
        val route = gate.route(request)
        val wasLocked = isLocked
        isLocked = gate.isLocked
        if (isLocked && !wasLocked) note("Too many wrong codes. Close Sync and open it again to get a new code.")

        return when (route) {
            SyncRoute.Page -> SyncResponse(200, "text/html; charset=utf-8", SyncPage.html.toByteArray(Charsets.UTF_8))
            is SyncRoute.Reply -> route.response
            SyncRoute.State -> stateResponse()
            is SyncRoute.SignIn -> signIn(route.server, route.username, route.password)
            SyncRoute.DownloadSettings -> downloadResponse()
            is SyncRoute.UploadSettings -> upload(route.data, route.choices)
        }
    }

    private fun stateResponse(): SyncResponse {
        if (activity.isEmpty()) note("A phone opened the page.")
        val account = app.client?.account ?: return SyncResponse.json(buildJsonObject { put("signedIn", false) })
        // Server and username only. The password never leaves the TV.
        return SyncResponse.json(
            buildJsonObject {
                put("server", account.serverURL)
                put("signedIn", true)
                put("username", account.username)
            }
        )
    }

    private suspend fun signIn(server: String, username: String, password: String): SyncResponse =
        try {
            app.signIn(server, username, password)
            note("Signed in as $username.")
            SyncResponse.message("Signed in. The TV is loading your channels.", true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SyncResponse.message(error.readable(), false)
        }

    private fun downloadResponse(): SyncResponse {
        val account = app.client?.account ?: return SyncResponse.message("Sign this TV in first.", false, 409)
        val file = SyncSettings(
            account = SyncSettings.Account(account.serverURL, account.username),
            hiddenCategories = app.categoryVisibility.visibility,
            favorites = app.favorites.channels,
            channelOrder = app.channelOrder.order,
        )
        note("Settings saved to the phone.")
        return SyncResponse(
            200,
            SyncResponse.JSON_TYPE,
            file.encoded(),
            listOf("Content-Disposition" to "attachment; filename=\"hilltv-settings.json\""),
        )
    }

    private fun upload(data: ByteArray, choices: SyncSettings.Choices): SyncResponse {
        val account = app.client?.account ?: return SyncResponse.message("Sign this TV in first.", false, 409)
        val file = try {
            SyncSettings.read(data)
        } catch (error: SyncSettings.ReadError) {
            return SyncResponse.message(error.message ?: "That isn't a HillTV settings file.", false)
        }
        if (!SyncSettings.sameServer(file.account.server, account.serverURL)) {
            return SyncResponse.message(
                "That file is from a different server (${file.account.server}). Its categories wouldn't match this TV's.",
                false,
                409,
            )
        }
        if (choices.hiddenCategories) app.categoryVisibility.replace(file.hiddenCategories)
        if (choices.favorites) app.favorites.replace(file.favorites)
        if (choices.channelOrder) app.channelOrder.replace(file.channelOrder)
        val summary = file.summary(choices)
        note(summary)
        return SyncResponse.message(summary, true)
    }

    companion object {
        /**
         * This TV's IPv4 address on the home network. Wi-Fi and wired
         * interfaces come first; VPNs, point-to-point links and
         * self-assigned 169.254 addresses are skipped, since a phone on the
         * Wi-Fi can't reach those.
         */
        fun localAddress(): String? {
            val candidates = ArrayList<Pair<String, String>>()
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            for (network in interfaces) {
                val usable = try {
                    network.isUp && !network.isLoopback && !network.isPointToPoint && !network.isVirtual
                } catch (_: Exception) {
                    false
                }
                if (!usable) continue
                for (address in network.inetAddresses.toList()) {
                    if (address !is Inet4Address || address.isLinkLocalAddress || address.isLoopbackAddress) continue
                    candidates.add(network.name to (address.hostAddress ?: continue))
                }
            }
            val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("eth") }
            return (preferred ?: candidates.firstOrNull())?.second
        }
    }
}
