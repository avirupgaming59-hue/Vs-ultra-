package com.example.workspace

import android.util.Log
import com.example.data.model.LocalhostRuntimeKind
import com.example.security.SecureVault
import com.example.util.SimpleJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val TAG = "LocalhostServerEngine"
private const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
private const val MAX_CONCURRENT_CLIENTS = 24
private const val VS_SHELL_SERVER_HEADER = "X-VSShell-Server"
private const val VS_SHELL_SERVER_ID = "vsshell-local-editor"

enum class PortProbeKind {
    FREE,
    EMBEDDED_VSSHELL_HEALTHY,
    EXTERNAL_HTTP_SERVER,
    OCCUPIED_NON_HTTP_OR_UNRESPONSIVE,
}

data class HttpProbeResult(
    val kind: PortProbeKind,
    val statusCode: Int = 0,
    val serverIdentity: String = "",
    val detail: String = "",
)

data class LocalhostServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val url: String = "",
    val isEmbeddedServer: Boolean = false,
    val runtimeKind: LocalhostRuntimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
    val sourceLabel: String = "",
    val workspaceDirPath: String = "",
    val termuxBootstrapCommand: String = DEFAULT_TERMUX_CODE_SERVER_CMD,
    val failureDetail: String = "",
) {
    companion object {
        const val DEFAULT_TERMUX_CODE_SERVER_CMD =
            "echo 'Create an external Termux workspace to generate its password-protected setup command.'"

        fun termuxCommandForPort(port: Int): String =
            "echo 'Create an external Termux workspace on port ${port.coerceIn(1024, 65535)} to generate its password-protected setup command.'"
    }
}

data class AiBuildResponse(
    val summary: String,
    val targetFileName: String,
    val updatedContent: String,
    val extraFiles: Map<String, String> = emptyMap(),
)

/**
 * Embedded HTTP/1.1 localhost workspace editor running on 127.0.0.1:<port>.
 * Truthfully exposes a local file editor + preview + AI endpoint (not full Linux code-server),
 * with strict HTTP health/identity probing, mutex-synchronized socket lifecycle,
 * per-session CSRF token, loopback Host/Origin checks, and path confinement.
 */
class LocalhostServerEngine(
    private val appFilesDir: File,
    val sharedFilesystem: com.example.runtime.SharedWorkspaceFilesystem = com.example.runtime.SharedWorkspaceFilesystem(appFilesDir),
) {

    private val accessPath = UUID.randomUUID().toString().replace("-", "")
    private val accessPathPrefix = "/$accessPath/"

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        runCatching { Log.w(TAG, "Caught background localhost coroutine exception: ${throwable.message}") }
    }
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val serverMutex = Mutex()
    private val socketLifecycleLock = Any()
    private val clientConcurrency = Semaphore(MAX_CONCURRENT_CLIENTS)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var boundPort: Int = -1

    @Volatile
    private var sessionToken: String = SecureVault.generateRandomHexToken(32)

    @Volatile
    private var activeRootDir: File = sharedFilesystem.resolveWorkspaceDir("default")

    @Volatile
    private var currentWorkspaceTitle: String = "Localhost Workspace"

    @Volatile
    private var currentWorkspaceSlug: String = "default"

    @Volatile
    private var workspaceRevision: Long = 1L

    @Volatile
    private var currentTermuxCommand: String = LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD

    @Volatile
    private var aiCodeHandler: (suspend (prompt: String, currentFile: String, currentContent: String, files: List<String>) -> Result<AiBuildResponse>)? = null

    @Volatile
    private var logSink: ((source: String, ansiMessage: String) -> Unit)? = null

    fun setAiCodeHandler(
        handler: suspend (prompt: String, currentFile: String, currentContent: String, files: List<String>) -> Result<AiBuildResponse>,
    ) {
        aiCodeHandler = handler
    }

    fun setLogSink(sink: (source: String, ansiMessage: String) -> Unit) {
        logSink = sink
    }

    private fun emitLog(source: String, ansiMessage: String) {
        runCatching { logSink?.invoke(source, ansiMessage) }
    }

    fun getWorkspaceDir(slug: String): File {
        return sharedFilesystem.resolveWorkspaceDir(slug)
    }

    /**
     * Probes 127.0.0.1:[port] over HTTP to distinguish a free port, our own embedded
     * VS Shell server, a valid external HTTP server (such as Termux code-server),
     * or an occupied non-HTTP / unresponsive socket.
     */
    suspend fun probeHttpIdentity(port: Int): HttpProbeResult = withContext(Dispatchers.IO) {
        if (port !in 1..65535) {
            return@withContext HttpProbeResult(
                kind = PortProbeKind.OCCUPIED_NON_HTTP_OR_UNRESPONSIVE,
                detail = "Invalid port $port",
            )
        }
        try {
            Socket().use { socket ->
                socket.soTimeout = 750
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 450)
                val out = socket.getOutputStream()
                out.write("HEAD / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()

                val reader = socket.getInputStream().bufferedReader()
                val statusLine = reader.readLine()?.trim().orEmpty()
                if (!statusLine.startsWith("HTTP/1.", ignoreCase = true)) {
                    return@withContext HttpProbeResult(
                        kind = PortProbeKind.OCCUPIED_NON_HTTP_OR_UNRESPONSIVE,
                        detail = "Port $port accepted TCP but did not respond with HTTP",
                    )
                }
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                val headers = linkedMapOf<String, String>()
                var count = 0
                while (count < 40) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                            line.substring(idx + 1).trim()
                    }
                    count++
                }
                val serverId = headers[VS_SHELL_SERVER_HEADER.lowercase(Locale.US)].orEmpty()
                when {
                    serverId == VS_SHELL_SERVER_ID -> HttpProbeResult(
                        kind = PortProbeKind.EMBEDDED_VSSHELL_HEALTHY,
                        statusCode = statusCode,
                        serverIdentity = serverId,
                        detail = "VS Shell built-in editor healthy ($statusCode)",
                    )
                    statusCode in 200..499 -> HttpProbeResult(
                        kind = PortProbeKind.EXTERNAL_HTTP_SERVER,
                        statusCode = statusCode,
                        serverIdentity = headers["server"].orEmpty(),
                        detail = "External HTTP server responded with HTTP $statusCode",
                    )
                    else -> HttpProbeResult(
                        kind = PortProbeKind.OCCUPIED_NON_HTTP_OR_UNRESPONSIVE,
                        statusCode = statusCode,
                        detail = "Unexpected HTTP status line: $statusLine",
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (connErr: ConnectException) {
            HttpProbeResult(
                kind = PortProbeKind.FREE,
                detail = connErr.message ?: "Port free",
            )
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("Connection refused", ignoreCase = true)) {
                HttpProbeResult(kind = PortProbeKind.FREE, detail = "Port free")
            } else {
                HttpProbeResult(
                    kind = PortProbeKind.OCCUPIED_NON_HTTP_OR_UNRESPONSIVE,
                    detail = t.localizedMessage ?: t.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Returns true ONLY when an external HTTP server (not our embedded server and not a raw non-HTTP socket)
     * is responding cleanly on 127.0.0.1:[port].
     */
    suspend fun isPortAcceptingConnections(port: Int): Boolean = withContext(Dispatchers.IO) {
        if (port !in 1..65535) return@withContext false
        if (boundPort == port && serverSocket != null && serverSocket?.isClosed == false) {
            return@withContext false
        }
        probeHttpIdentity(port).kind == PortProbeKind.EXTERNAL_HTTP_SERVER
    }

    fun isEmbeddedServerHealthy(port: Int): Boolean {
        if (port !in 1..65535) return false
        val sock = serverSocket ?: return false
        val job = acceptJob ?: return false
        return boundPort == port && sock.isBound && !sock.isClosed && job.isActive
    }

    fun isEmbeddedServerRunning(): Boolean {
        val port = boundPort
        return port in 1..65535 && isEmbeddedServerHealthy(port)
    }

    /**
     * Verifies via real loopback GET /api/info with session token that our embedded server
     * returns HTTP 200 OK, our identity header, and valid workspace JSON.
     */
    suspend fun probeLocalhostHttp(port: Int): Boolean = withContext(Dispatchers.IO) {
        if (port !in 1..65535) return@withContext false
        try {
            Socket().use { socket ->
                socket.soTimeout = 900
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 500)
                val out = socket.getOutputStream()
                val req = buildString {
                    append("GET /api/info HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("X-VSShell-Token: $sessionToken\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(req.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val (headerBlock, body) = com.example.runtime.VsCodeRuntimeController.readBoundedHttpResponse(socket.getInputStream())
                val firstLine = headerBlock.lineSequence().firstOrNull().orEmpty()
                val has200 = firstLine.startsWith("HTTP/1.") && firstLine.contains("200")
                val hasIdentityHeader = headerBlock.contains("$VS_SHELL_SERVER_HEADER: $VS_SHELL_SERVER_ID", ignoreCase = true)
                if (!has200 || !hasIdentityHeader) {
                    return@withContext false
                }
                val json = runCatching { SimpleJson.parseObject(body) }.getOrNull() ?: return@withContext false
                SimpleJson.stringValue(json, "slug").isNotBlank()
            }
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun ensureLocalhostReady(
        requestedPort: Int,
        workspaceName: String,
        workspaceSlug: String,
        termuxCommand: String = currentTermuxCommand,
        starterFiles: Map<String, String> = emptyMap(),
        allowExternalAttach: Boolean = true,
        allowPortFallback: Boolean = true,
        overwriteStarterFiles: Boolean = false,
    ): LocalhostServerStatus = withContext(Dispatchers.IO) {
        serverMutex.withLock {
            val targetPort = requestedPort.coerceIn(1024, 65535)
            emitLog("SERVER", "\u001B[36mChecking localhost 127.0.0.1:$targetPort for workspace \"$workspaceName\"...\u001B[0m")
            val sanitizedSlug = sharedFilesystem.sanitizeSlug(workspaceSlug)
            val dir = sharedFilesystem.setActiveWorkspace(sanitizedSlug, "fallback-viewer")

            try {
                if (starterFiles.isNotEmpty()) {
                    val written = writeStarterFiles(dir, starterFiles, overwriteExisting = overwriteStarterFiles)
                    emitLog("SERVER", "\u001B[32mPrepared $written starter file(s) in ${dir.name}\u001B[0m")
                } else if (dir.listFiles()?.isEmpty() != false) {
                    val defaults = defaultStarterFiles(workspaceName, targetPort, termuxCommand)
                    writeStarterFiles(dir, defaults, overwriteExisting = false)
                    emitLog("SERVER", "\u001B[32mCreated ${defaults.size} fallback viewer starter file(s) in ${dir.name}\u001B[0m")
                }
            } catch (t: Throwable) {
                val msg = "Workspace file write failure: ${t.localizedMessage ?: t.javaClass.simpleName}"
                emitLog("SERVER", "\u001B[31m$msg\u001B[0m")
                return@withLock LocalhostServerStatus(
                    isRunning = false,
                    port = targetPort,
                    url = "http://127.0.0.1:$targetPort",
                    isEmbeddedServer = true,
                    runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                    sourceLabel = msg,
                    workspaceDirPath = dir.absolutePath,
                    termuxBootstrapCommand = termuxCommand,
                    failureDetail = msg,
                )
            }

            currentWorkspaceTitle = workspaceName
            currentWorkspaceSlug = sanitizedSlug
            currentTermuxCommand = termuxCommand
            activeRootDir = dir
            workspaceRevision += 1L

            // Check if our own fallback server is already healthy on targetPort
            val existingSocket = serverSocket
            if (existingSocket != null && !existingSocket.isClosed && boundPort == targetPort && acceptJob?.isActive == true) {
                if (probeLocalhostHttp(boundPort)) {
                    emitLog("SERVER", "\u001B[90mVerified Fallback File Viewer on http://127.0.0.1:$boundPort\u001B[0m")
                    return@withLock LocalhostServerStatus(
                        isRunning = true,
                        port = boundPort,
                        url = "http://127.0.0.1:$boundPort",
                        isEmbeddedServer = true,
                        runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                        sourceLabel = "Fallback File Viewer (127.0.0.1:$boundPort)",
                        workspaceDirPath = dir.absolutePath,
                        termuxBootstrapCommand = termuxCommand,
                    )
                }
            }

            // Probe port over HTTP before binding so we never attach to an invalid non-HTTP TCP socket
            val portProbe = probeHttpIdentity(targetPort)
            if (allowExternalAttach && portProbe.kind == PortProbeKind.EXTERNAL_HTTP_SERVER) {
                emitLog("TERMUX", "\u001B[32mVerified external HTTP server on 127.0.0.1:$targetPort (HTTP ${portProbe.statusCode}); attaching.\u001B[0m")
                return@withLock LocalhostServerStatus(
                    isRunning = true,
                    port = targetPort,
                    url = "http://127.0.0.1:$targetPort",
                    isEmbeddedServer = false,
                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                    sourceLabel = "Termux / External HTTP Server (127.0.0.1:$targetPort)",
                    workspaceDirPath = dir.absolutePath,
                    termuxBootstrapCommand = termuxCommand,
                )
            }

            stopInternal()
            sessionToken = SecureVault.generateRandomHexToken(32)

            val socket = bindSocketSafely(
                targetPort = targetPort,
                allowPortFallback = allowPortFallback,
                probeDetail = portProbe.detail,
            )

            if (socket == null) {
                val errMsg = "Failed to bind localhost socket on port $targetPort (${portProbe.detail.ifBlank { "port busy" }})"
                emitLog("SERVER", "\u001B[31m$errMsg\u001B[0m")
                return@withLock LocalhostServerStatus(
                    isRunning = false,
                    port = targetPort,
                    url = "http://127.0.0.1:$targetPort",
                    isEmbeddedServer = true,
                    runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                    sourceLabel = "Port $targetPort unavailable",
                    workspaceDirPath = dir.absolutePath,
                    termuxBootstrapCommand = termuxCommand,
                    failureDetail = errMsg,
                )
            }

            synchronized(socketLifecycleLock) {
                serverSocket = socket
                boundPort = socket.localPort
                acceptJob = serverScope.launch {
                    while (isActive && !socket.isClosed) {
                        try {
                            val client = socket.accept()
                            serverScope.launch(exceptionHandler) {
                                if (!clientConcurrency.tryAcquire()) {
                                    runCatching { client.close() }
                                    return@launch
                                }
                                try {
                                    handleClientSafely(client)
                                } finally {
                                    clientConcurrency.release()
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Throwable) {
                            if (!isActive || socket.isClosed) {
                                break
                            }
                            runCatching { Log.w(TAG, "Transient accept warning: ${e.message}") }
                        }
                    }
                }
            }

            // Live post-bind HTTP self-verification before ever reporting running = true
            var httpVerified = false
            for (attempt in 0 until 4) {
                if (probeLocalhostHttp(boundPort)) {
                    httpVerified = true
                    break
                }
                delay(40L)
            }

            if (!httpVerified) {
                val verifyErr = "Embedded server bound port $boundPort but failed HTTP /api/info health check"
                emitLog("SERVER", "\u001B[31m$verifyErr\u001B[0m")
                stopInternal()
                return@withLock LocalhostServerStatus(
                    isRunning = false,
                    port = targetPort,
                    url = "http://127.0.0.1:$targetPort",
                    isEmbeddedServer = true,
                    runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                    sourceLabel = verifyErr,
                    workspaceDirPath = dir.absolutePath,
                    termuxBootstrapCommand = termuxCommand,
                    failureDetail = verifyErr,
                )
            }

            emitLog("SERVER", "\u001B[1;32mFallback File Viewer verified at http://127.0.0.1:$boundPort (root=${dir.name})\u001B[0m")
            LocalhostServerStatus(
                isRunning = true,
                port = boundPort,
                url = "http://127.0.0.1:$boundPort/$accessPath/",
                isEmbeddedServer = true,
                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                sourceLabel = "Fallback File Viewer (127.0.0.1:$boundPort)",
                workspaceDirPath = dir.absolutePath,
                termuxBootstrapCommand = termuxCommand,
            )
        }
    }

    private fun bindSocketSafely(
        targetPort: Int,
        allowPortFallback: Boolean,
        probeDetail: String,
    ): ServerSocket? {
        return runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 50)
            }
        }.recoverCatching { err ->
            if (!allowPortFallback) throw err
            emitLog("SERVER", "\u001B[33mPort $targetPort busy ($probeDetail); binding ephemeral free localhost port...\u001B[0m")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
            }
        }.getOrNull()
    }

    /**
     * Writes starter files into [dir]. By default preserves existing non-empty user files
     * ([overwriteExisting] = false) so retries/recovery never clobber user work.
     */
    fun writeStarterFiles(
        dir: File,
        files: Map<String, String>,
        overwriteExisting: Boolean = false,
    ): Int {
        val writtenCount = sharedFilesystem.writeFiles(
            workspaceDir = dir,
            files = files,
            overwriteExisting = overwriteExisting,
            source = "fallback-viewer",
        )
        if (writtenCount > 0) {
            workspaceRevision += 1L
        }
        return writtenCount
    }

    suspend fun stopSuspending() = withContext(Dispatchers.IO) {
        serverMutex.withLock {
            stopInternal()
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        val prevPort: Int
        synchronized(socketLifecycleLock) {
            prevPort = boundPort
            acceptJob?.cancel()
            acceptJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
            boundPort = -1
        }
        if (prevPort > 0) {
            emitLog("SERVER", "\u001B[33mStopped built-in local editor on port $prevPort\u001B[0m")
        }
    }

    private suspend fun handleClientSafely(client: Socket) {
        try {
            handleClient(client)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { Log.d(TAG, "Client socket closed cleanly: ${t.javaClass.simpleName}: ${t.message}") }
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val rawRequest = readHttpRequest(input) ?: return
            if (rawRequest.payloadTooLarge) {
                writeAndFlush(sock, httpResponse(413, "application/json; charset=utf-8", """{"error":"Payload too large"}"""))
                return
            }

            val firstLine = rawRequest.headerLines.firstOrNull().orEmpty()
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val fullTarget = parts[1]
            val requestedPath = fullTarget.substringBefore("?")
            val pathIsAuthorized = requestedPath.startsWith(accessPathPrefix)
            val path = if (pathIsAuthorized) {
                requestedPath.removePrefix("/$accessPath").ifBlank { "/" }
            } else {
                ""
            }
            val query = parseQuery(fullTarget.substringAfter("?", ""))
            val rootSnapshot = activeRootDir

            if (!isAllowedLoopbackHost(rawRequest.headers["host"]) ||
                !isAllowedLoopbackOrigin(rawRequest.headers["origin"])
            ) {
                emitLog("SECURITY", "\u001B[31mBlocked untrusted Host/Origin on $method $path\u001B[0m")
                writeAndFlush(
                    sock,
                    httpResponse(403, "application/json; charset=utf-8", """{"error":"Forbidden host or origin"}"""),
                )
                return
            }

            if (path == "/" || (path.startsWith("/api/") && path != "/api/files")) {
                emitLog("HTTP", "\u001B[90m$method $path\u001B[0m")
            }

            val isProtectedApi = path.startsWith("/api/") && !(method == "HEAD" && path == "/api/info")
            if (isProtectedApi && !hasValidSessionToken(rawRequest.headers["x-vsshell-token"])) {
                emitLog("SECURITY", "\u001B[33mRejected unauthorized API call: $method $path\u001B[0m")
                writeAndFlush(
                    sock,
                    httpResponse(403, "application/json; charset=utf-8", """{"error":"Unauthorized localhost API token"}"""),
                )
                return
            }

            val response = when {
                method == "HEAD" && (path == "/" || path == "/api/info") -> {
                    httpResponse(200, "text/html; charset=utf-8", "", includeBody = false)
                }
                method == "GET" && path == "/" -> {
                    httpResponse(200, "text/html; charset=utf-8", buildIdeHtml(sessionToken))
                }
                method == "GET" && path.startsWith("/preview") -> {
                    servePreviewResource(rootSnapshot, path)
                }
                method == "GET" && path == "/api/info" -> {
                    val infoJson = buildString {
                        append('{')
                        append("\"name\":").append(jsonQuote(currentWorkspaceTitle)).append(',')
                        append("\"slug\":").append(jsonQuote(currentWorkspaceSlug)).append(',')
                        append("\"port\":").append(boundPort).append(',')
                        append("\"revision\":").append(workspaceRevision).append(',')
                        append("\"mode\":\"Fallback File Viewer (Not VS Code)\",")
                        append("\"termuxCommand\":").append(jsonQuote(currentTermuxCommand))
                        append('}')
                    }
                    httpResponse(200, "application/json; charset=utf-8", infoJson)
                }
                method == "GET" && path == "/api/files" -> {
                    try {
                        val list = sharedFilesystem.listFiles(rootSnapshot, includeNested = true)
                        val filesJson = buildString {
                            append('[')
                            list.forEachIndexed { idx, f ->
                                if (idx > 0) append(',')
                                val relPath = sharedFilesystem.relativePathOf(rootSnapshot, f)
                                append("{\"name\":").append(jsonQuote(relPath))
                                    .append(",\"size\":").append(f.length())
                                    .append(",\"mtime\":").append(f.lastModified())
                                    .append('}')
                            }
                            append(']')
                        }
                        httpResponse(200, "application/json; charset=utf-8", filesJson)
                    } catch (t: Throwable) {
                        httpResponse(500, "application/json; charset=utf-8", """{"error":${jsonQuote(t.localizedMessage ?: "Failed to list files")}}""")
                    }
                }
                method == "GET" && path == "/api/file" -> {
                    val name = sanitizeRelativeFileName(query["name"].orEmpty())
                    val target = resolveConfinedFile(rootSnapshot, name)
                    if (name.isBlank() || target == null || !target.exists() || !target.isFile) {
                        httpResponse(404, "application/json; charset=utf-8", """{"error":"File not found"}""")
                    } else {
                        try {
                            val content = target.readText(StandardCharsets.UTF_8)
                            val fileJson = buildString {
                                append("{\"name\":").append(jsonQuote(name))
                                append(",\"content\":").append(jsonQuote(content))
                                append(",\"mtime\":").append(target.lastModified()).append('}')
                            }
                            httpResponse(200, "application/json; charset=utf-8", fileJson)
                        } catch (t: Throwable) {
                            httpResponse(500, "application/json; charset=utf-8", """{"error":${jsonQuote(t.localizedMessage ?: "Failed to read file")}}""")
                        }
                    }
                }
                method == "POST" && path == "/api/save" -> {
                    val bodyMap = runCatching { SimpleJson.parseObject(rawRequest.body) }.getOrNull()
                    val name = sanitizeRelativeFileName(bodyMap?.let { SimpleJson.stringValue(it, "name") }.orEmpty())
                    val content = bodyMap?.let { SimpleJson.stringValue(it, "content") }.orEmpty()
                    val target = resolveConfinedFile(rootSnapshot, name)
                    if (name.isBlank() || target == null) {
                        httpResponse(400, "application/json; charset=utf-8", """{"error":"Invalid filename"}""")
                    } else {
                        try {
                            target.parentFile?.mkdirs()
                            target.writeText(content, StandardCharsets.UTF_8)
                            workspaceRevision += 1L
                            sharedFilesystem.notifyChanged("fallback-viewer", currentWorkspaceSlug, "Saved $name")
                            val saveJson = """{"ok":true,"name":${jsonQuote(name)},"mtime":${target.lastModified()}}"""
                            httpResponse(200, "application/json; charset=utf-8", saveJson)
                        } catch (t: Throwable) {
                            httpResponse(500, "application/json; charset=utf-8", """{"error":${jsonQuote(t.localizedMessage ?: "Save failed")}}""")
                        }
                    }
                }
                method == "POST" && path == "/api/ai" -> {
                    val bodyMap = runCatching { SimpleJson.parseObject(rawRequest.body) }.getOrNull()
                    val prompt = bodyMap?.let { SimpleJson.stringValue(it, "prompt") }.orEmpty().trim()
                    val currentFile = bodyMap?.let { SimpleJson.stringValue(it, "currentFile") }.orEmpty()
                    val currentContent = bodyMap?.let { SimpleJson.stringValue(it, "currentContent") }.orEmpty()
                    val handler = aiCodeHandler
                    if (prompt.isBlank() || handler == null) {
                        httpResponse(
                            400,
                            "application/json; charset=utf-8",
                            """{"error":"AI prompt or handler unavailable"}""",
                        )
                    } else {
                        val fileNames = sharedFilesystem.listFiles(rootSnapshot, includeNested = true)
                            .map { sharedFilesystem.relativePathOf(rootSnapshot, it) }
                        val result = handler.invoke(prompt, currentFile, currentContent, fileNames)
                        result.fold(
                            onSuccess = { build ->
                                val safeName = sanitizeRelativeFileName(build.targetFileName).ifBlank { "index.html" }
                                val target = resolveConfinedFile(rootSnapshot, safeName)
                                if (target == null) {
                                    httpResponse(400, "application/json; charset=utf-8", """{"error":"Unsafe target filename"}""")
                                } else {
                                    try {
                                        target.parentFile?.mkdirs()
                                        target.writeText(build.updatedContent, StandardCharsets.UTF_8)
                                        val writtenNames = mutableListOf(safeName)
                                        build.extraFiles.forEach { (extraRaw, extraContent) ->
                                            val extraSafe = sanitizeRelativeFileName(extraRaw)
                                            val extraTarget = resolveConfinedFile(rootSnapshot, extraSafe)
                                            if (extraSafe.isNotBlank() && extraTarget != null) {
                                                extraTarget.parentFile?.mkdirs()
                                                extraTarget.writeText(extraContent, StandardCharsets.UTF_8)
                                                writtenNames.add(extraSafe)
                                            }
                                        }
                                        workspaceRevision += 1L
                                        sharedFilesystem.notifyChanged("fallback-viewer-ai", currentWorkspaceSlug, "AI built $safeName")
                                        val aiSuccessJson = buildString {
                                            append("{\"ok\":true")
                                            append(",\"summary\":").append(jsonQuote(build.summary))
                                            append(",\"name\":").append(jsonQuote(safeName))
                                            append(",\"content\":").append(jsonQuote(build.updatedContent))
                                            append(",\"createdFiles\":[").append(writtenNames.joinToString(",") { jsonQuote(it) }).append(']')
                                            append('}')
                                        }
                                        httpResponse(200, "application/json; charset=utf-8", aiSuccessJson)
                                    } catch (t: Throwable) {
                                        httpResponse(500, "application/json; charset=utf-8", """{"error":${jsonQuote(t.localizedMessage ?: "Failed writing AI output")}}""")
                                    }
                                }
                            },
                            onFailure = { err ->
                                httpResponse(
                                    500,
                                    "application/json; charset=utf-8",
                                    """{"error":${jsonQuote(err.localizedMessage ?: "AI generation failed")}}""",
                                )
                            },
                        )
                    }
                }
                method == "DELETE" && path == "/api/file" -> {
                    val name = sanitizeRelativeFileName(query["name"].orEmpty())
                    val target = resolveConfinedFile(rootSnapshot, name)
                    if (name.isBlank() || target == null) {
                        httpResponse(400, "application/json; charset=utf-8", """{"error":"Invalid filename"}""")
                    } else {
                        try {
                            if (target.exists()) {
                                target.delete()
                                workspaceRevision += 1L
                                sharedFilesystem.notifyChanged("fallback-viewer", currentWorkspaceSlug, "Deleted $name")
                            }
                            httpResponse(200, "application/json; charset=utf-8", """{"ok":true}""")
                        } catch (t: Throwable) {
                            httpResponse(500, "application/json; charset=utf-8", """{"error":${jsonQuote(t.localizedMessage ?: "Delete failed")}}""")
                        }
                    }
                }
                else -> httpResponse(404, "text/plain; charset=utf-8", "Not Found")
            }

            writeAndFlush(sock, response)
        }
    }

    private fun servePreviewResource(rootDir: File, path: String): ByteArray {
        val rawRel = path.removePrefix("/preview").trimStart('/')
        val cleanName = if (rawRel.isBlank()) "index.html" else sanitizeRelativeFileName(rawRel)
        val target = resolveConfinedFile(rootDir, cleanName)
        if (cleanName.isBlank() || target == null || !target.exists() || !target.isFile) {
            return httpResponse(404, "text/plain; charset=utf-8", "Preview file not found")
        }
        val mime = when (cleanName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            else -> "text/plain; charset=utf-8"
        }
        val content = target.readBytes()
        return httpBinaryResponse(
            code = 200,
            contentType = mime,
            bodyBytes = content,
            extraHeaders = mapOf(
                "Content-Security-Policy" to "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src 'none';",
            ),
        )
    }

    private fun writeAndFlush(sock: Socket, bytes: ByteArray) {
        sock.getOutputStream().apply {
            write(bytes)
            flush()
        }
    }

    internal fun isAllowedLoopbackHost(hostHeader: String?): Boolean {
        val raw = hostHeader?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.isBlank()) return false
        val hostOnly = if (raw.startsWith("[")) {
            raw.substringBefore(']') + "]"
        } else {
            raw.substringBefore(':')
        }
        return hostOnly == "127.0.0.1" || hostOnly == "localhost" || hostOnly == "[::1]"
    }

    internal fun isAllowedLoopbackOrigin(originHeader: String?): Boolean {
        val raw = originHeader?.trim().orEmpty()
        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return true
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            (scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost")
        }.getOrDefault(false)
    }

    private fun hasValidSessionToken(provided: String?): Boolean {
        val clean = provided?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return MessageDigest.isEqual(
            sessionToken.toByteArray(StandardCharsets.UTF_8),
            clean.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private data class ParsedHttpRequest(
        val headerLines: List<String>,
        val headers: Map<String, String>,
        val body: String,
        val payloadTooLarge: Boolean = false,
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedHttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var prev1 = -1
        var prev2 = -1
        var prev3 = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            if (headerBytes.size() > 64 * 1024) return null
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase(Locale.US)
                val value = line.substring(idx + 1).trim()
                headers[key] = value
                if (key == "content-length") {
                    contentLength = value.toIntOrNull() ?: -1
                }
            }
        }
        if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
            return ParsedHttpRequest(lines, headers, "", payloadTooLarge = contentLength > MAX_REQUEST_BODY_BYTES)
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = input.read(buf, readTotal, contentLength - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            String(buf, 0, readTotal, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return ParsedHttpRequest(lines, headers, body, payloadTooLarge = false)
    }

    private fun httpResponse(
        code: Int,
        contentType: String,
        body: String,
        includeBody: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        return httpBinaryResponse(
            code = code,
            contentType = contentType,
            bodyBytes = body.toByteArray(StandardCharsets.UTF_8),
            includeBody = includeBody,
            extraHeaders = extraHeaders,
        )
    }

    private fun httpBinaryResponse(
        code: Int,
        contentType: String,
        bodyBytes: ByteArray,
        includeBody: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("$VS_SHELL_SERVER_HEADER: $VS_SHELL_SERVER_ID\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n")
            append("Pragma: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: SAMEORIGIN\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            extraHeaders.forEach { (k, v) ->
                append("$k: $v\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return if (includeBody) headers + bodyBytes else headers
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        rawQuery.split("&").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                runCatching {
                    val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    result[k] = v
                }
            }
        }
        return result
    }

    internal fun sanitizeRelativeFileName(raw: String): String {
        return sharedFilesystem.sanitizeRelativePath(raw)
    }

    internal fun resolveConfinedFile(rootDir: File, rawFileName: String): File? {
        return sharedFilesystem.resolveConfinedFile(rootDir, rawFileName)
    }

    private fun jsonQuote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun escapeHtml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    fun defaultStarterFiles(name: String, port: Int, termuxCmd: String): Map<String, String> {
        val safeName = escapeHtml(name)
        return linkedMapOf(
            "index.html" to """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>$safeName</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #111827; color: #f9fafb; padding: 24px; }
                    .card { background: #1f2937; border: 1px solid #374151; border-radius: 12px; padding: 18px; }
                    code { color: #38bdf8; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>$safeName</h1>
                    <p>Built-in local editor serving on <code>http://127.0.0.1:$port</code>.</p>
                  </div>
                </body>
                </html>
            """.trimIndent(),
            "start-termux-codeserver.sh" to """
                #!/data/data/com.termux/files/usr/bin/bash
                $termuxCmd
            """.trimIndent(),
            "README.md" to """
                # $name

                Local file workspace served by VS Shell's Built-in Local Editor on `http://127.0.0.1:$port`.

                ## Optional: Full Linux code-server in Termux
                This built-in server edits and previews static/local project files on Android.
                For Node.js, Python runtime execution, or VS Code extensions, run this command manually inside Termux:
                ```bash
                $termuxCmd
                ```
            """.trimIndent(),
        )
    }

    private fun buildIdeHtml(token: String): String {
        val safeHexToken = token.filter { it in '0'..'9' || it in 'a'..'f' }
        val quotedToken = "\"$safeHexToken\""
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
<title>VS Shell Fallback File Viewer</title>
<style>
  :root {
    --bg: #1e1e1e; --sidebar: #252526; --panel: #2d2d30;
    --border: #3c3c3c; --text: #d4d4d4; --muted: #9ca3af; --accent: #007acc; --ai: #7c3aed;
  }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: var(--bg); color: var(--text); height: 100vh; display: flex; flex-direction: column; }
  .fallback-banner { background: #3b2f10; border-bottom: 1px solid #92400e; color: #fde68a; font-size: 11px; padding: 5px 10px; text-align: center; }
  header { background: var(--panel); border-bottom: 1px solid var(--border); padding: 8px 12px; display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
  .title { font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .pill { font-size: 11px; background: rgba(0,122,204,.22); color: #60a5fa; padding: 2px 8px; border-radius: 999px; }
  .pill.ai { background: rgba(124,58,237,.24); color: #c4b5fd; }
  .actions { display: flex; gap: 6px; flex-wrap: wrap; }
  button { background: var(--accent); color: #fff; border: 0; border-radius: 6px; padding: 6px 10px; font-size: 12px; cursor: pointer; }
  button.secondary { background: #3c3c3c; color: var(--text); }
  button.ai-btn { background: var(--ai); color: #fff; }
  button:disabled { opacity: 0.55; cursor: wait; }
  .ai-bar { background: #1a1b26; border-bottom: 1px solid var(--border); padding: 7px 10px; display: flex; gap: 8px; align-items: center; }
  .ai-bar input { flex: 1; background: #111827; color: #f3f4f6; border: 1px solid #374151; border-radius: 6px; padding: 7px 10px; font-size: 12px; outline: none; }
  .workspace { flex: 1; display: flex; min-height: 0; }
  aside { width: 170px; background: var(--sidebar); border-right: 1px solid var(--border); overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 4px; }
  .file-item { text-align: left; background: transparent; color: var(--text); padding: 7px 8px; border-radius: 6px; font-size: 12px; width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .file-item.active { background: #37373d; color: #fff; font-weight: 600; }
  main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
  .tabbar { background: var(--sidebar); border-bottom: 1px solid var(--border); padding: 6px 10px; font-size: 12px; color: var(--muted); display: flex; justify-content: space-between; align-items: center; gap: 8px; }
  textarea { flex: 1; width: 100%; background: var(--bg); color: #e5e7eb; border: 0; padding: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; line-height: 1.5; resize: none; outline: none; }
  iframe { flex: 1; width: 100%; border: 0; background: #fff; display: none; }
  .termux-bar { background: #141416; border-top: 1px solid var(--border); padding: 6px 10px; font-family: ui-monospace, monospace; font-size: 11px; color: #93c5fd; white-space: nowrap; overflow-x: auto; }
  @media (max-width: 560px) { aside { width: 124px; } }
</style>
</head>
<body>
<div class="fallback-banner">Emergency Local File Viewer (Fallback Only — Not full VS Code / code-server). Shared workspace synchronized with User Terminal & Phone Files.</div>
<header>
  <div class="title">
    <span id="wsName">Fallback File Viewer</span>
    <span class="pill ai" id="wsMode">Fallback Viewer</span>
    <span class="pill" id="wsPort">127.0.0.1</span>
  </div>
  <div class="actions">
    <button class="secondary" onclick="createFile()">+ File</button>
    <button class="secondary" onclick="deleteCurrent()">Delete</button>
    <button class="secondary" id="previewToggleBtn" onclick="togglePreview()">Preview</button>
    <button onclick="saveCurrent()">Save</button>
  </div>
</header>
<div class="ai-bar">
  <input id="aiPrompt" placeholder="Ask Gemini to edit or generate a file in this workspace..." onkeydown="if(event.key==='Enter') runAiEdit()" />
  <button class="ai-btn" id="aiRunBtn" onclick="runAiEdit()">AI Build</button>
</div>
<div class="workspace">
  <aside id="fileList"></aside>
  <main>
    <div class="tabbar"><span id="currentFile">Select a file</span><span id="status">Ready</span></div>
    <textarea id="editor" spellcheck="false" placeholder="Select or create a file, or ask AI Build above to generate code..."></textarea>
    <iframe id="previewFrame" sandbox="allow-scripts allow-forms"></iframe>
  </main>
</div>
<div class="termux-bar" id="termuxCmdBar">Fallback Local File Viewer active — use User Terminal or Termux code-server for full shell/IDE runtime</div>
<script>
const SESSION_TOKEN = $quotedToken;
let files = [];
let activeFile = null;
let activeFileMtime = 0;
let isDirty = false;
let previewOpen = false;
let lastFileKey = '';
let currentWorkspaceSlug = null;
let currentWorkspaceRev = null;

document.getElementById('editor').addEventListener('input', () => {
  isDirty = true;
  document.getElementById('status').textContent = 'Modified ' + (activeFile || '');
});

function apiFetch(url, options = {}) {
  const headers = Object.assign({ 'X-VSShell-Token': SESSION_TOKEN }, options.headers || {});
  return fetch(url, Object.assign({ cache: 'no-store' }, options, { headers }));
}

async function syncWorkspaceInfo() {
  const res = await apiFetch('/api/info');
  if (!res.ok) return { switched: false, revChanged: false };
  const info = await res.json();
  document.getElementById('wsName').textContent = info.name;
  document.getElementById('wsPort').textContent = '127.0.0.1:' + info.port;
  document.getElementById('termuxCmdBar').textContent = 'Termux command: ' + info.termuxCommand;
  const switched = currentWorkspaceSlug !== null && info.slug !== currentWorkspaceSlug;
  const revChanged = currentWorkspaceRev !== null && info.revision !== currentWorkspaceRev;
  currentWorkspaceSlug = info.slug;
  currentWorkspaceRev = info.revision;
  if (switched) {
    activeFile = null;
    activeFileMtime = 0;
    isDirty = false;
    lastFileKey = '';
  } else if (revChanged) {
    lastFileKey = '';
  }
  return { switched, revChanged };
}

async function init() {
  try {
    await syncWorkspaceInfo();
    await refreshFiles(true, false);
    setInterval(() => pollWorkspace(), 2500);
  } catch (e) {
    document.getElementById('status').textContent = 'Connection error: ' + (e.message || 'unreachable');
  }
}

async function pollWorkspace() {
  try {
    const { switched, revChanged } = await syncWorkspaceInfo();
    await refreshFiles(switched, revChanged);
  } catch (e) {}
}

async function refreshFiles(openFirst, forceReloadOpenFile = false) {
  try {
    const res = await apiFetch('/api/files');
    if (!res.ok) {
      document.getElementById('status').textContent = 'Failed to list files (' + res.status + ')';
      return;
    }
    const nextFiles = await res.json();
    const key = nextFiles.map(f => f.name + ':' + f.size + ':' + f.mtime).join('|');
    if (key !== lastFileKey || forceReloadOpenFile) {
      lastFileKey = key;
      files = nextFiles;
      const list = document.getElementById('fileList');
      list.innerHTML = '';
      files.forEach(f => {
        const btn = document.createElement('button');
        btn.className = 'file-item' + (f.name === activeFile ? ' active' : '');
        btn.textContent = f.name;
        btn.onclick = () => openFile(f.name);
        list.appendChild(btn);
      });
      if ((openFirst || !activeFile) && files.length > 0) {
        const preferred = files.find(f => f.name === 'index.html') || files[0];
        openFile(preferred.name);
      } else if (activeFile && !isDirty) {
        const currentMeta = files.find(f => f.name === activeFile);
        if (currentMeta && (forceReloadOpenFile || currentMeta.mtime !== activeFileMtime)) {
          openFile(activeFile);
        }
      }
    }
  } catch (e) {
    document.getElementById('status').textContent = 'File refresh failed';
  }
}

async function openFile(name) {
  try {
    const res = await apiFetch('/api/file?name=' + encodeURIComponent(name));
    if (!res.ok) {
      document.getElementById('status').textContent = 'Open failed (HTTP ' + res.status + ')';
      return;
    }
    const data = await res.json();
    activeFile = data.name;
    activeFileMtime = data.mtime || 0;
    isDirty = false;
    document.getElementById('currentFile').textContent = data.name;
    document.getElementById('editor').value = data.content || '';
    document.getElementById('status').textContent = 'Loaded ' + data.name;
    Array.from(document.querySelectorAll('#fileList .file-item')).forEach(btn => {
      btn.classList.toggle('active', btn.textContent === activeFile);
    });
    if (previewOpen) updatePreview();
  } catch (e) {
    document.getElementById('status').textContent = 'Open error: ' + (e.message || 'failed');
  }
}

async function saveCurrent() {
  if (!activeFile) {
    document.getElementById('status').textContent = 'Select or create a file first';
    return;
  }
  try {
    const content = document.getElementById('editor').value;
    const res = await apiFetch('/api/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: activeFile, content })
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      activeFileMtime = data.mtime || Date.now();
      isDirty = false;
      document.getElementById('status').textContent = 'Saved ' + activeFile;
      if (previewOpen) updatePreview();
      lastFileKey = '';
      refreshFiles(false);
    } else {
      document.getElementById('status').textContent = data.error || ('Save failed (' + res.status + ')');
    }
  } catch (e) {
    document.getElementById('status').textContent = 'Save error: ' + (e.message || 'failed');
  }
}

async function createFile() {
  const name = prompt('New file name (e.g. app.js, main.py, index.html):', 'app.js');
  if (!name || !name.trim()) return;
  try {
    const clean = name.trim();
    const res = await apiFetch('/api/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: clean, content: '' })
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      lastFileKey = '';
      await refreshFiles(false);
      openFile(clean);
    } else {
      document.getElementById('status').textContent = data.error || ('Create failed (' + res.status + ')');
    }
  } catch (e) {
    document.getElementById('status').textContent = 'Create error: ' + (e.message || 'failed');
  }
}

async function deleteCurrent() {
  if (!activeFile) return;
  if (!confirm('Delete ' + activeFile + '?')) return;
  try {
    const res = await apiFetch('/api/file?name=' + encodeURIComponent(activeFile), { method: 'DELETE' });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      activeFile = null;
      activeFileMtime = 0;
      isDirty = false;
      document.getElementById('currentFile').textContent = 'Select a file';
      document.getElementById('editor').value = '';
      document.getElementById('status').textContent = 'Deleted';
      lastFileKey = '';
      refreshFiles(true);
    } else {
      document.getElementById('status').textContent = data.error || ('Delete failed (' + res.status + ')');
    }
  } catch (e) {
    document.getElementById('status').textContent = 'Delete error: ' + (e.message || 'failed');
  }
}

async function runAiEdit() {
  const input = document.getElementById('aiPrompt');
  const btn = document.getElementById('aiRunBtn');
  const promptText = (input.value || '').trim();
  if (!promptText) return;
  btn.disabled = true;
  document.getElementById('status').textContent = 'Gemini is editing...';
  const controller = new AbortController();
  const timeoutHandle = setTimeout(() => controller.abort(), 70000);
  try {
    const res = await apiFetch('/api/ai', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      body: JSON.stringify({
        prompt: promptText,
        currentFile: activeFile || 'index.html',
        currentContent: document.getElementById('editor').value || ''
      })
    });
    const rawText = await res.text();
    let data = {};
    try { data = JSON.parse(rawText); } catch (_) {}
    if (res.ok && data.ok) {
      input.value = '';
      activeFile = data.name;
      activeFileMtime = Date.now();
      isDirty = false;
      document.getElementById('currentFile').textContent = data.name;
      document.getElementById('editor').value = data.content || '';
      document.getElementById('status').textContent = data.summary || ('AI updated ' + data.name);
      if (previewOpen) updatePreview();
      lastFileKey = '';
      refreshFiles(false);
    } else {
      document.getElementById('status').textContent = data.error || ('AI error (HTTP ' + res.status + ')');
    }
  } catch (e) {
    document.getElementById('status').textContent = e.name === 'AbortError'
      ? 'AI request timed out — retry'
      : ('AI request failed: ' + (e.message || 'network error'));
  } finally {
    clearTimeout(timeoutHandle);
    btn.disabled = false;
  }
}

function togglePreview() {
  previewOpen = !previewOpen;
  document.getElementById('editor').style.display = previewOpen ? 'none' : 'block';
  document.getElementById('previewFrame').style.display = previewOpen ? 'block' : 'none';
  const btn = document.getElementById('previewToggleBtn');
  if (btn) btn.textContent = previewOpen ? 'Editor' : 'Preview';
  if (previewOpen) updatePreview();
}

function escapeHtmlText(raw) {
  return String(raw || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function updatePreview() {
  const frame = document.getElementById('previewFrame');
  const htmlFile = (activeFile && /\.html?$/i.test(activeFile))
    ? activeFile
    : ((files.find(f => /^index\.html?$/i.test(f.name)) || files.find(f => /\.html?$/i.test(f.name)))?.name || null);
  if (htmlFile) {
    frame.removeAttribute('srcdoc');
    frame.src = '/preview/' + encodeURIComponent(htmlFile) + '?t=' + Date.now();
  } else {
    frame.removeAttribute('src');
    const escaped = escapeHtmlText(document.getElementById('editor').value);
    frame.srcdoc = '<!doctype html><html><body style="margin:0;padding:16px;background:#111827;color:#e5e7eb;font:13px/1.5 ui-monospace,monospace;"><pre style="white-space:pre-wrap;word-break:break-word;">' + escaped + '</pre></body></html>';
  }
}

init();
</script>
</body>
</html>
        """.trimIndent()
    }
}
