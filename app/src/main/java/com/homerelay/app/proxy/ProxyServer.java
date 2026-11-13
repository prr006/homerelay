package com.homerelay.app.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HomeRelay's local HTTP forward proxy. Pure Java — no android.* imports — so the
 * exact code that ships in the app is unit-tested on the JVM by TestRunner.
 *
 * <h2>Supported surfaces</h2>
 * <ul>
 *   <li>{@code CONNECT host:port} tunneling (HTTPS and any TLS-based protocol).
 *   <li>Plain HTTP with absolute-form targets (proxies always receive) and
 *       origin-form + Host header (defensive compatibility).
 *   <li>Local information endpoints on the bind address: {@code /} (info page),
 *       {@code /proxy.pac}, {@code /status.json}. Served only for requests whose
 *       Host header matches this proxy's bind host:port.</li>
 * </ul>
 *
 * <h2>Guarantee: Wi-Fi only</h2>
 * Every outbound socket is created by the injected {@link OutboundConnector}, which the
 * Android layer implements by binding sockets to the upstream Wi-Fi Network object.
 * If no upstream Wi-Fi is available the connector throws, and the client gets a 503 —
 * the proxy never falls back to mobile data or the default network.
 *
 * <h2>Backpressure & limits</h2>
 * Bounded concurrency (64), bounded head size (64 KiB), bounded body (32 MiB),
 * 16 KiB copy buffers, idle timeouts, deterministic socket cleanup.
 */
public final class ProxyServer {

    public interface OutboundConnector {
        /**
         * Open a TCP connection to {@code host}:{@code port} bound to the upstream
         * Wi-Fi network (never cellular / never the hotspot network).
         * Implementations must apply the supplied timeout and throw IOException on failure.
         */
        Socket open(String host, int port, int connectTimeoutMs) throws IOException;
    }

    public interface DnsResolver {
        /** Resolve via the upstream network. Throws UnknownHostException on failure. */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public interface Logger {
        void d(String message);
        void w(String message);
    }

    public static final class Config {
        public int connectTimeoutMs = 10_000;
        public int headReadTimeoutMs = 30_000;
        public int originReadTimeoutMs = 120_000;
        public int tunnelIdleTimeoutMs = 300_000;
        public int maxHeadBytes = 64 * 1024;
        public int maxBodyBytes = 32 * 1024 * 1024;
        public int maxClients = 64;
        public String viaHeader = "HomeRelay";
        public String pacHost = null;      // host advertised inside the PAC file
        public int pacPort = 8282;
        public String infoTitle = "HomeRelay";
    }

    private final Config config;
    private final OutboundConnector connector;
    private final DnsResolver dns;
    private final Logger logger;
    private final ProxyStats stats = new ProxyStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ServerSocket> serverSockets = new ArrayList<>();
    private final List<Socket> clientSockets = new ArrayList<>();
    private ExecutorService bossPool;
    private ExecutorService workerPool;
    private Semaphore clientPermits;

    public ProxyServer(Config config, OutboundConnector connector, DnsResolver dns, Logger logger) {
        if (config == null || connector == null || dns == null) {
            throw new NullPointerException("config, connector and dns are required");
        }
        this.config = config;
        this.connector = connector;
        this.dns = dns;
        this.logger = logger == null ? new Logger() {
            @Override public void d(String message) { }
            @Override public void w(String message) { }
        } : logger;
    }

    public ProxyStats stats() { return stats; }
    public boolean isRunning() { return running.get(); }
    public int boundPort() {
        synchronized (serverSockets) {
            for (ServerSocket s : serverSockets) return s.getLocalPort();
        }
        return -1;
    }

    /** Start listening on the given hosts. Throws IOException if no host could be bound. */
    public void start(List<String> bindHosts, int port) throws IOException {
        if (!running.compareAndSet(false, true)) return;
        clientPermits = new Semaphore(config.maxClients, true);
        List<ServerSocket> opened = new ArrayList<>();
        IOException firstError = null;
        for (String host : bindHosts) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(host, port), 16);
                opened.add(ss);
            } catch (IOException e) {
                if (firstError == null) firstError = e;
                logger.w("Cannot bind proxy to " + host + ": " + e);
            }
        }
        if (opened.isEmpty()) {
            running.set(false);
            throw firstError != null ? firstError : new IOException("no bind addresses");
        }
        synchronized (serverSockets) { serverSockets.addAll(opened); }
        bossPool = Executors.newFixedThreadPool(opened.size(), daemonThreadFactory("homerelay-proxy-boss"));
        workerPool = Executors.newCachedThreadPool(daemonThreadFactory("homerelay-proxy-worker"));
        for (ServerSocket ss : opened) {
            bossPool.execute(() -> acceptLoop(ss));
        }
        logger.d("Proxy listening on " + opened.size() + " address(es), port " + port);
    }

    /** Stop everything and close all tracked sockets. */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        synchronized (serverSockets) {
            for (ServerSocket s : serverSockets) try { s.close(); } catch (IOException ignored) {}
            serverSockets.clear();
        }
        synchronized (clientSockets) {
            for (Socket s : clientSockets) try { s.close(); } catch (IOException ignored) {}
            clientSockets.clear();
        }
        shutdownExecutor(bossPool);
        shutdownExecutor(workerPool);
        bossPool = null;
        workerPool = null;
        logger.d("Proxy stopped");
    }

    private void shutdownExecutor(ExecutorService pool) {
        if (pool == null) return;
        pool.shutdownNow();
        try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (running.get()) {
            final Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) logger.w("Accept failed: " + e);
                return;
            }
            if (!clientPermits.tryAcquire()) {
                rejectQuietly(client);
                continue;
            }
            synchronized (clientSockets) { clientSockets.add(client); }
            stats.connectionOpened();
            try {
                workerPool.execute(() -> handleClient(client));
            } catch (RejectedExecutionException e) {
                releaseClient(client);
                rejectQuietly(client);
            }
        }
    }

    private void rejectQuietly(Socket client) {
        try {
            byte[] body = "Proxy busy\r\n".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\nVia: " + config.viaHeader + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            client.getOutputStream().write(out.toByteArray());
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // best effort
        } finally {
            closeQuietly(client);
        }
    }

    private void releaseClient(Socket client) {
        stats.connectionClosed();
        synchronized (clientSockets) { clientSockets.remove(client); }
        if (clientPermits != null) clientPermits.release();
        closeQuietly(client);
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread t = new Thread(runnable, name + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /* ================= client handling ================= */

    private void handleClient(Socket client) {
        String clientIp = String.valueOf(client.getInetAddress());
        stats.noteClient(clientIp);
        try {
            client.setSoTimeout(config.headReadTimeoutMs);
            client.setTcpNoDelay(true);
            SocketInput in = new SocketInput(client.getInputStream(), config.maxHeadBytes);
            String headBlock = in.readHeadBlock();
            if (headBlock == null) {
                return; // client closed without sending anything
            }
            stats.addBytesUp(headBlock.getBytes(StandardCharsets.ISO_8859_1).length);
            Head head = Head.parse(headBlock);
            String startLine = head.startLine();
            int sp1 = startLine.indexOf(' ');
            if (sp1 <= 0) throw new BadRequest("malformed start line");
            String method = startLine.substring(0, sp1).toUpperCase(java.util.Locale.US);
            int sp2 = startLine.indexOf(' ', sp1 + 1);
            String target = sp2 > 0 ? startLine.substring(sp1 + 1, sp2) : startLine.substring(sp1 + 1);

            if ("CONNECT".equals(method)) {
                handleConnect(client, head, target, method);
                return; // connection lifecycle transferred to tunnel; finally{} only releases client
            }
            handlePlainHttp(client, in, head, method, target);
        } catch (BadRequest e) {
            sendErrorAndClose(client, 400, e.getMessage());
        } catch (SocketTimeoutException e) {
            sendErrorAndClose(client, 504, "Upstream timed out");
        } catch (UnknownHostException e) {
            sendErrorAndClose(client, 502, "DNS resolution failed");
        } catch (Exception e) {
            logger.w("Proxy error: " + e);
            sendErrorAndClose(client, 502, "Bad gateway");
        } finally {
            releaseClient(client);
        }
    }

    private void handleConnect(Socket client, Head head, String target, String method) throws IOException {
        int colon = target.lastIndexOf(':');
        if (colon <= 0 || colon == target.length() - 1) {
            throw new BadRequest("CONNECT target must be host:port");
        }
        String host = target.substring(0, colon).trim();
        int port = parsePort(target.substring(colon + 1).trim());
        if (host.isEmpty() || hasControlChars(host) || port == -1) {
            throw new BadRequest("Invalid CONNECT target");
        }
        stats.incrementRequests();
        Socket upstream = resolveAndConnect(host, port);
        stats.incrementConnectTunnels();
        stats.incrementSuccessful();

        OutputStream clientOut = client.getOutputStream();
        String established = "HTTP/1.1 200 Connection Established\r\nVia: " + config.viaHeader + "\r\n\r\n";
        byte[] establishedBytes = established.getBytes(StandardCharsets.ISO_8859_1);
        clientOut.write(establishedBytes);
        clientOut.flush();
        stats.addBytesDown(establishedBytes.length);

        client.setSoTimeout(config.tunnelIdleTimeoutMs);
        upstream.setSoTimeout(config.tunnelIdleTimeoutMs);
        pumpTunnel(client, clientOut, upstream);
    }

    /** Full-duplex byte pump between client and upstream; returns when the tunnel closes. */
    private void pumpTunnel(Socket client, OutputStream clientOut, Socket upstream) throws IOException {
        InputStream clientIn = client.getInputStream();
        InputStream upstreamIn = upstream.getInputStream();
        OutputStream upstreamOut = upstream.getOutputStream();

        AtomicBoolean upDone = new AtomicBoolean(false);
        AtomicBoolean downDone = new AtomicBoolean(false);
        Socket[] both = {client, upstream};
        Thread up = new Thread(() -> {
            try {
                pumpCounting(clientIn, upstreamOut, true);
            } catch (IOException ignored) {
            } finally {
                upDone.set(true);
                closeSocket(both[1]);
            }
        }, "homerelay-tunnel-up");
        Thread down = new Thread(() -> {
            try {
                pumpCounting(upstreamIn, clientOut, false);
            } catch (IOException ignored) {
            } finally {
                downDone.set(true);
                closeSocket(both[0]);
            }
        }, "homerelay-tunnel-down");
        up.setDaemon(true);
        down.setDaemon(true);
        up.start();
        down.start();
        while (up.isAlive() || down.isAlive()) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (upDone.get() && downDone.get()) break;
        }
        closeSocket(upstream);
    }

    private long copy(InputStream from, OutputStream to, int idleTimeoutMs) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = from.read(buffer)) != -1) {
            to.write(buffer, 0, read);
            to.flush();
            total += read;
        }
        return total;
    }

    /** Pump bytes while accounting for them live (used by tunnels). */
    private void pumpCounting(InputStream from, OutputStream to, boolean upload) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = from.read(buffer)) != -1) {
            to.write(buffer, 0, read);
            to.flush();
            if (upload) stats.addBytesUp(read); else stats.addBytesDown(read);
        }
    }

    private void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    /* ---------- plain HTTP ---------- */

    private boolean handlePlainHttp(Socket client, SocketInput in, Head head, String method, String target)
            throws IOException {
        TargetUrl url = parseTargetUrl(target, head);
        if (url == null) {
            throw new BadRequest("Plain HTTP requests must use absolute-form (http://host/path) or origin-form with a Host header");
        }
        stats.incrementRequests();

        // Local endpoints served directly by the proxy (never forwarded).
        if (isSelfTarget(client, url)) {
            serveLocalEndpoint(client, url.path, head);
            stats.incrementSuccessful();
            return true;
        }

        // Read the request body (if any), decoding chunked transfer encoding.
        boolean expectContinue = "100-continue".equalsIgnoreCase(trimOrEmpty(head.get("Expect")));
        if (expectContinue) {
            String cont = "HTTP/1.1 100 Continue\r\n\r\n";
            client.getOutputStream().write(cont.getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
        }
        byte[] body = readRequestBody(in, head);
        if (body == null) {
            sendErrorAndClose(client, 413, "Request body too large");
            return true;
        }

        // Build the upstream request.
        String originPath = (url.path == null || url.path.isEmpty()) ? "/" : url.path;
        StringBuilder startLine = new StringBuilder()
                .append(method).append(' ').append(originPath)
                .append(url.query != null ? "?" + url.query : "")
                .append(" HTTP/1.1");
        Head out = Head.of(startLine.toString());
        out.setAll("Host", java.util.Collections.singletonList(url.hostHeaderValue()));
        out.set("Via", config.viaHeader);
        out.set("Connection", "close");
        java.util.Map<String, java.util.List<String>> inHeaders = head.asMap();
        for (java.util.Map.Entry<String, java.util.List<String>> e : inHeaders.entrySet()) {
            String name = e.getKey();
            if (equalsIgnoreCase(name, "Host") || equalsIgnoreCase(name, "Connection")
                    || equalsIgnoreCase(name, "Via")) continue;
            for (String v : e.getValue()) {
                if (isHopByHop(name)) continue;
                out.add(name, v);
            }
        }
        out.setAll("Content-Length", java.util.Collections.singletonList(String.valueOf(body.length)));

        Socket upstream = resolveAndConnect(url.host, url.port);
        try {
            OutputStream upstreamOut = upstream.getOutputStream();
            byte[] headBytes = out.toBytes();
            upstreamOut.write(headBytes);
            upstreamOut.write(body);
            upstreamOut.flush();
            stats.addBytesUp(headBytes.length + body.length);

            upstream.setSoTimeout(config.originReadTimeoutMs);
            SocketInput originIn = new SocketInput(upstream.getInputStream(), config.maxHeadBytes);
            String originHeadBlock = originIn.readHeadBlock();
            if (originHeadBlock == null) {
                throw new IOException("origin closed without a response head");
            }
            Head originHead = Head.parse(originHeadBlock);
            stats.addBytesDown(originHeadBlock.getBytes(StandardCharsets.ISO_8859_1).length);

            // Relay the response head, connection: close so clients read to EOF.
            String originStatusLine = originHead.startLine();
            Head relayed = Head.of(originStatusLine);
            for (java.util.Map.Entry<String, java.util.List<String>> e : originHead.asMap().entrySet()) {
                String name = e.getKey();
                if (equalsIgnoreCase(name, "Connection") || isHopByHop(name)) continue;
                for (String v : e.getValue()) relayed.add(name, v);
            }
            relayed.set("Connection", "close");
            relayed.set("Via", config.viaHeader);
            byte[] relayHead = relayed.toBytes();
            OutputStream clientOut = client.getOutputStream();
            clientOut.write(relayHead);
            clientOut.flush();
            stats.addBytesDown(relayHead.length);

            long copied = copy(originIn.asStream(), clientOut, config.originReadTimeoutMs);
            stats.addBytesDown(copied);
            stats.incrementSuccessful();
            return true;
        } finally {
            closeSocket(upstream);
        }
    }

    /** Decode request body per Content-Length or chunked encoding. Returns null if too large. */
    private byte[] readRequestBody(SocketInput in, Head head) throws IOException {
        String len = trimOrEmpty(head.get("Content-Length"));
        String te = trimOrEmpty(head.get("Transfer-Encoding"));
        if (te.toLowerCase(java.util.Locale.US).contains("chunked")) {
            return readChunked(in);
        }
        if (len.isEmpty()) return new byte[0];
        int length;
        try {
            length = Integer.parseInt(len);
        } catch (NumberFormatException e) {
            throw new BadRequest("Bad Content-Length");
        }
        if (length < 0 || length > config.maxBodyBytes) return null;
        return in.readExact(length, config.maxBodyBytes);
    }

    private byte[] readChunked(SocketInput in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] lineBuf = new byte[8192];
        for (;;) {
            String line = in.readLine(8192);
            if (line == null) throw new IOException("unexpected eof in chunk header");
            int semicolon = line.indexOf(';');
            String sizeHex = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            if (sizeHex.isEmpty()) throw new IOException("empty chunk size");
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeHex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeHex);
            }
            if (chunkSize == 0) {
                // Consume trailers until the blank line.
                for (;;) {
                    String trailer = in.readLine(8192);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return out.toByteArray();
            }
            if (chunkSize > config.maxBodyBytes - (long) out.size()) return null;
            byte[] chunk = in.readExact((int) chunkSize, config.maxBodyBytes);
            out.write(chunk);
            byte[] crlf = in.readExact(2, config.maxBodyBytes);
            if (crlf[0] != '\r' || crlf[1] != '\n') throw new IOException("missing CRLF after chunk");
        }
    }

    /* ================= local (self) endpoints ================= */

    private boolean isSelfTarget(Socket client, TargetUrl url) {
        int localPort = client.getLocalPort();
        String host = url.host == null ? "" : url.host.toLowerCase(java.util.Locale.US);
        boolean hostIsLocal = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        if (hostIsLocal && url.port == localPort) return true;
        String pacHost = config.pacHost == null ? "" : config.pacHost.toLowerCase(java.util.Locale.US);
        return !pacHost.isEmpty() && host.equals(pacHost) && url.port == localPort;
    }

    private void serveLocalEndpoint(Socket client, String path, Head head) throws IOException {
        String p = path == null ? "/" : path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if ("/proxy.pac".equals(p)) {
            String pacHost = config.pacHost == null ? "192.168.43.1" : config.pacHost;
            byte[] body = PacProvider.pacBytes(pacHost, config.pacPort);
            sendLocalResponse(client, 200, "application/x-ns-proxy-autoconfig", body);
            return;
        }
        if ("/status.json".equals(p)) {
            byte[] body = statusJson().getBytes(StandardCharsets.UTF_8);
            sendLocalResponse(client, 200, "application/json", body);
            return;
        }
        if ("/".equals(p) || "/index.html".equals(p)) {
            byte[] body = infoPage().getBytes(StandardCharsets.UTF_8);
            sendLocalResponse(client, 200, "text/html; charset=utf-8", body);
            return;
        }
        sendLocalResponse(client, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private void sendLocalResponse(Socket client, int code, String contentType, byte[] body) throws IOException {
        String status = code == 200 ? "200 OK" : code == 404 ? "404 Not Found" : code + " ";
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Via: " + config.viaHeader + "\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private String statusJson() {
        ProxyStats.Snapshot s = stats.snapshot();
        return "{\"service\":\"HomeRelay\",\"requests\":" + s.totalRequests
                + ",\"connectTunnels\":" + s.connectTunnels
                + ",\"bytesUp\":" + s.bytesUp
                + ",\"bytesDown\":" + s.bytesDown
                + ",\"activeConnections\":" + s.activeConnections
                + ",\"seenDevices\":" + s.seenDevices
                + "}";
    }

    private String infoPage() {
        String pacHost = config.pacHost == null ? "192.168.43.1" : config.pacHost;
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>HomeRelay</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{font-family:sans-serif;background:#0f1b1e;color:#e8f0f2;margin:0;padding:24px}"
                + "code{background:#1d2c31;padding:2px 6px;border-radius:4px}h1{color:#37c8d4}</style></head>"
                + "<body><h1>HomeRelay</h1><p>This device is sharing its Wi-Fi internet through a local proxy.</p>"
                + "<p><b>Proxy:</b> <code>" + pacHost + ":" + config.pacPort + "</code></p>"
                + "<p><b>Auto-config (PAC):</b> <code>http://" + pacHost + ":" + config.pacPort + "/proxy.pac</code></p>"
                + "<p>Status (JSON): <code>/status.json</code></p>"
                + "<p>Set the Wi-Fi proxy on your client device to the address above, or use the PAC URL as its auto-config script.</p>"
                + "</body></html>";
    }

    /* ================= upstream plumbing ================= */

    private Socket resolveAndConnect(String host, int port) throws IOException {
        InetAddress[] addrs;
        try {
            addrs = dns.resolve(host);
        } catch (UnknownHostException e) {
            stats.incrementFailed();
            throw e;
        }
        if (addrs == null || addrs.length == 0) {
            stats.incrementFailed();
            throw new UnknownHostException(host);
        }
        IOException lastError = null;
        for (InetAddress addr : addrs) {
            try {
                return connector.open(addr.getHostAddress(), port, config.connectTimeoutMs);
            } catch (IOException e) {
                lastError = e;
                logger.d("Connect to " + addr.getHostAddress() + ":" + port + " failed: " + e);
            }
        }
        stats.incrementFailed();
        throw lastError != null ? lastError : new IOException("could not connect to " + host + ":" + port);
    }

    /* ================= helpers ================= */

    private boolean isHopByHop(String name) {
        switch (name.toLowerCase(java.util.Locale.US)) {
            case "connection":
            case "keep-alive":
            case "proxy-connection":
            case "proxy-authenticate":
            case "proxy-authorization":
            case "te":
            case "trailer":
            case "transfer-encoding":
            case "upgrade":
            case "expect":
                return true;
            default:
                return false;
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == ' ' || c == '\t') return true;
        }
        return false;
    }

    private static int parsePort(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) return -1;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return -1;
        }
        try {
            int p = Integer.parseInt(text);
            return p >= 1 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void sendErrorAndClose(Socket client, int code, String message) {
        stats.incrementFailed();
        try {
            String body = code + " " + (message == null ? "" : message) + "\r\n";
            String statusText = code == 400 ? "Bad Request"
                    : code == 413 ? "Payload Too Large"
                    : code == 502 ? "Bad Gateway"
                    : code == 503 ? "Service Unavailable"
                    : code == 504 ? "Gateway Timeout" : "Error";
            String response = "HTTP/1.1 " + code + " " + statusText + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n"
                    + "Via: " + config.viaHeader + "\r\n\r\n" + body;
            client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // client is gone; nothing to send
        }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    /** Target of a plain HTTP request. */
    private static final class TargetUrl {
        final String host;
        final int port;
        final String path;
        final String query;

        TargetUrl(String host, int port, String path, String query) {
            this.host = host;
            this.port = port;
            this.path = path;
            this.query = query;
        }

        String hostHeaderValue() {
            return port == 80 ? host : host + ":" + port;
        }
    }

    private static TargetUrl parseTargetUrl(String target, Head head) {
        if (target == null) return null;
        String t = target.trim();
        if (t.startsWith("http://")) {
            int pathStart = t.indexOf('/', "http://".length());
            String authority = pathStart < 0 ? t.substring("http://".length()) : t.substring("http://".length(), pathStart);
            String remainder = pathStart < 0 ? "/" : t.substring(pathStart);
            // Strip any userinfo (user:pass@host) — proxies must not leak or log it.
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            int colon = authority.lastIndexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            int port = 80;
            if (colon >= 0) {
                port = parsePort(authority.substring(colon + 1));
                if (port == -1) return null;
            }
            if (host.isEmpty() || hasControlChars(host)) return null;
            int q = remainder.indexOf('?');
            String path = q < 0 ? remainder : remainder.substring(0, q);
            String query = q < 0 ? null : remainder.substring(q + 1);
            return new TargetUrl(host, port, path, query);
        }
        if (t.startsWith("/")) {
            // origin-form: rely on the Host header
            String hostHeader = trimOrEmpty(head.get("Host"));
            if (hostHeader.isEmpty()) return null;
            int at = hostHeader.lastIndexOf('@');
            if (at >= 0) hostHeader = hostHeader.substring(at + 1);
            int colon = hostHeader.lastIndexOf(':');
            String host = colon < 0 ? hostHeader : hostHeader.substring(0, colon);
            int port = 80;
            if (colon >= 0) {
                port = parsePort(hostHeader.substring(colon + 1));
                if (port == -1) return null;
            }
            if (host.isEmpty() || hasControlChars(host)) return null;
            int q = t.indexOf('?');
            String path = q < 0 ? t : t.substring(0, q);
            String query = q < 0 ? null : t.substring(q + 1);
            return new TargetUrl(host, port, path, query);
        }
        return null;
    }

    /** Stream wrapper with a pushback buffer. Head and body reads share the buffered window. */
    static final class SocketInput {
        private final InputStream raw;
        private byte[] buffer = new byte[8192];
        private int start;
        private int end;
        private final int maxHeadBytes;

        // Tiny automata for finding the head terminator anywhere in the stream.
        private int crlfState;   // 0..4, matches CRLFCRLF
        private int lfState;     // 0..2, matches LFLF

        SocketInput(InputStream raw, int maxHeadBytes) {
            this.raw = raw;
            this.maxHeadBytes = maxHeadBytes;
        }

        /** Read until the head terminator (CRLFCRLF or LFLF). Returns null on immediate EOF. */
        String readHeadBlock() throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
            byte[] chunk = new byte[4096];
            for (;;) {
                int n = raw.read(chunk);
                if (n == -1) {
                    if (head.size() == 0) return null;
                    return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                for (int i = 0; i < n; i++) {
                    byte b = chunk[i];
                    boolean crlfMatch = feedCrlf(b);
                    boolean lfMatch = feedLf(b);
                    head.write(b);
                    if (head.size() > maxHeadBytes) {
                        throw new BadRequest("Headers too large");
                    }
                    if (crlfMatch || lfMatch) {
                        int remain = n - i - 1;
                        if (remain > 0) {
                            buffer = new byte[remain];
                            System.arraycopy(chunk, i + 1, buffer, 0, remain);
                            start = 0;
                            end = remain;
                        } else {
                            start = 0;
                            end = 0;
                        }
                        return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
                    }
                }
            }
        }

        private boolean feedCrlf(byte b) {
            switch (crlfState) {
                case 0:
                    if (b == '\r') crlfState = 1;
                    return false;
                case 1:
                    if (b == '\n') { crlfState = 2; return false; }
                    crlfState = (b == '\r') ? 1 : 0;
                    return false;
                case 2:
                    if (b == '\r') { crlfState = 3; return false; }
                    crlfState = (b == '\r') ? 1 : 0;
                    return false;
                case 3:
                    if (b == '\n') { crlfState = 0; return true; }
                    crlfState = (b == '\r') ? 1 : 0;
                    return false;
                default:
                    crlfState = 0;
                    return false;
            }
        }

        private boolean feedLf(byte b) {
            if (b == '\n') {
                if (lfState == 1) { lfState = 0; return true; }
                lfState = 1;
            } else if (b != '\r') {
                lfState = 0;
            }
            return false;
        }

        int bufferedBytes() {
            return end - start;
        }

        byte[] readExact(int count, int max) throws IOException {
            if (count > max) throw new IOException("body too large");
            byte[] out = new byte[count];
            int off = 0;
            while (off < count) {
                int buffered = end - start;
                if (buffered > 0) {
                    int take = Math.min(buffered, count - off);
                    System.arraycopy(buffer, start, out, off, take);
                    start += take;
                    off += take;
                } else {
                    int n = raw.read(out, off, count - off);
                    if (n == -1) throw new IOException("unexpected eof reading body");
                    off += n;
                }
            }
            return out;
        }

        String readLine(int maxLen) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            for (int i = 0; i < maxLen; i++) {
                int b = readByte();
                if (b == -1) {
                    return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                if (b == '\n') {
                    byte[] bytes = line.toByteArray();
                    int len = bytes.length;
                    while (len > 0 && bytes[len - 1] == '\r') len--;
                    return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
                }
                line.write(b);
            }
            throw new IOException("line too long");
        }

        int readByte() throws IOException {
            if (start >= end) {
                int n = raw.read(buffer, 0, buffer.length);
                if (n == -1) return -1;
                start = 0;
                end = n;
            }
            return buffer[start++] & 0xFF;
        }

        InputStream asStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return readByte();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    if (start < end) {
                        int take = Math.min(end - start, len);
                        System.arraycopy(buffer, start, b, off, take);
                        start += take;
                        return take;
                    }
                    return raw.read(b, off, len);
                }

                @Override
                public boolean markSupported() { return false; }
            };
        }
    }
}
