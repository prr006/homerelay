package com.homerelay.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, dependency-free HTTP proxy probe used by the plain-HTTP diagnostic.
 *
 * <p>Speaks HTTP/1.1 in absolute-form to the local HomeRelay proxy (i.e. exactly what a
 * browser does when configured to use an HTTP proxy):
 *
 * <pre>GET http://host/path HTTP/1.1\r\nHost: host\r\nConnection: close\r\n\r\n</pre>
 *
 * <p>Sending the probe through a direct {@link Socket} — instead of {@code HttpURLConnection}
 * — keeps working on API 36+ where cleartext traffic is blocked app-wide by default: the
 * bytes to the local proxy are not "cleartext HTTP to connectivitycheck.gstatic.com", they
 * are an ordinary TCP stream to the user's own loopback-side proxy. The proxy then fetches
 * the target over the pinned upstream Wi-Fi.
 *
 * <p>Failures are reported as {@link Result} values, never thrown: connection refused,
 * host unreachable, read timeout, malformed status line/headers, over-size responses.
 */
public final class ProxyHttpProbe {
    private ProxyHttpProbe() {}

    public static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;

    /** Outcome of one probe. {@code error == null} iff {@link #success}. */
    public static final class Result {
        public final boolean success;
        public final int statusCode;          // 0 when no status line could be parsed
        public final String statusLine;       // the raw status line, or null
        public final Map<String, String> headers; // lower-cased names; empty when unparsable
        public final String error;            // null on success

        private Result(boolean success, int statusCode, String statusLine,
                       Map<String, String> headers, String error) {
            this.success = success;
            this.statusCode = statusCode;
            this.statusLine = statusLine;
            this.headers = headers;
            this.error = error;
        }

        public static Result ok(int statusCode, String statusLine, Map<String, String> headers) {
            return new Result(true, statusCode, statusLine,
                Collections.unmodifiableMap(new LinkedHashMap<>(headers)), null);
        }

        public static Result failure(String error) {
            return new Result(false, 0, null, Collections.<String, String>emptyMap(), error);
        }
    }

    /**
     * Perform a single absolute-form GET through the proxy at {@code host:port}.
     *
     * @param absoluteUri     e.g. {@code http://connectivitycheck.gstatic.com/generate_204}
     * @param hostHeader      value for the {@code Host:} header (usually {@code connectivitycheck.gstatic.com})
     * @param connectTimeoutMs socket connect timeout
     * @param readTimeoutMs    socket read timeout (applied per read call)
     */
    public static Result probeGet(String host, int port, String absoluteUri, String hostHeader,
                                  int connectTimeoutMs, int readTimeoutMs) {
        return probeGet(host, port, absoluteUri, hostHeader, connectTimeoutMs, readTimeoutMs,
            DEFAULT_MAX_RESPONSE_BYTES);
    }

    public static Result probeGet(String host, int port, String absoluteUri, String hostHeader,
                                  int connectTimeoutMs, int readTimeoutMs, int maxResponseBytes) {
        if (host == null || host.isEmpty()) {
            return Result.failure("No proxy host configured (bind address missing).");
        }
        if (port <= 0 || port > 65535) {
            return Result.failure("Invalid proxy port: " + port);
        }
        String request = buildRequest(absoluteUri, hostHeader);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            try (OutputStream out = socket.getOutputStream()) {
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            byte[] raw = readResponse(socket.getInputStream(), maxResponseBytes);
            return parseResponse(raw);
        } catch (SocketTimeoutException e) {
            return Result.failure("Timed out talking to the proxy at " + host + ":" + port + " (" +
                (e.getMessage() != null ? e.getMessage() : "read or connect timeout") + ").");
        } catch (java.net.ConnectException e) {
            return Result.failure("Connection refused by " + host + ":" + port +
                " — the proxy is not listening there (" + e.getMessage() + ").");
        } catch (java.net.NoRouteToHostException | java.net.PortUnreachableException e) {
            return Result.failure("Cannot reach " + host + ":" + port + " (" + e.getMessage() + ").");
        } catch (java.net.UnknownHostException e) {
            return Result.failure("Cannot resolve proxy host '" + host + "' (" + e.getMessage() + ").");
        } catch (IOException e) {
            String msg = e.getMessage();
            return Result.failure("I/O error talking to the proxy: " + (msg != null ? msg : e.getClass().getSimpleName()));
        } catch (SecurityException e) {
            return Result.failure("Blocked by the system security policy: " + e.getMessage());
        }
    }

    /** The exact request bytes sent to the proxy (exposed for tests/diagnostics). */
    public static String buildRequest(String absoluteUri, String hostHeader) {
        return "GET " + absoluteUri + " HTTP/1.1\r\n"
            + "Host: " + hostHeader + "\r\n"
            + "User-Agent: HomeRelay-Diagnostics/1.0\r\n"
            + "Accept: */*\r\n"
            + "Connection: close\r\n"
            + "\r\n";
    }

    /**
     * Read until EOF, until we have a complete header block + any declared Content-Length
     * body, or until {@code maxBytes} have been consumed. Caller owns the stream.
     */
    static byte[] readResponse(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        byte[] chunk = new byte[4096];
        int total = 0;
        int headerEnd = -1;
        while (total < maxBytes) {
            int remaining = maxBytes - total;
            int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (n < 0) break; // EOF — proxy closed (we sent Connection: close)
            buf.write(chunk, 0, n);
            total += n;
            if (headerEnd < 0) {
                headerEnd = indexOf(buf.toByteArray(), "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                if (headerEnd >= 0 && responseComplete(buf.toByteArray(), headerEnd)) {
                    break; // headers + declared body fully read
                }
            } else if (responseComplete(buf.toByteArray(), headerEnd)) {
                break;
            }
        }
        return buf.toByteArray();
    }

    /** True when we have headers plus every declared body byte (or a bodyless status). */
    static boolean responseComplete(byte[] raw, int headerEnd) {
        // headerEnd is the index of the \r\n\r\n sequence; body starts at headerEnd + 4
        String head = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
        int firstLineEnd = head.indexOf("\r\n");
        if (firstLineEnd < 0) firstLineEnd = head.length();
        String statusLine = head.substring(0, firstLineEnd);
        int code = parseStatusCode(statusLine);
        // Status codes with no body: 1xx, 204, 304.
        if ((code >= 100 && code < 200) || code == 204 || code == 304) {
            return true;
        }
        long contentLength = parseContentLength(head);
        if (contentLength < 0) {
            // Unknown length (absent header or chunked): accept what we have once headers
            // arrived; hanging until read-timeout would turn a working path into a FAIL.
            return true;
        }
        long bodyHave = raw.length - (headerEnd + 4L);
        return bodyHave >= contentLength;
    }

    static int parseStatusCode(String statusLine) {
        if (statusLine == null) return 0;
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length < 2) return 0;
        try {
            int code = Integer.parseInt(parts[1]);
            return (code >= 100 && code <= 599) ? code : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long parseContentLength(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().toLowerCase(Locale.US).equals("content-length")) {
                try {
                    long v = Long.parseLong(line.substring(colon + 1).trim());
                    return v >= 0 ? v : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Parse the HTTP response; success iff a well-formed status line with a 1xx–5xx code. */
    public static Result parseResponse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return Result.failure("Empty response from the proxy (connection closed without data).");
        }
        String head = new String(raw, 0, Math.min(raw.length, 8192), StandardCharsets.ISO_8859_1);
        int headerEnd = head.indexOf("\r\n\r\n");
        String statusLineBlock = headerEnd >= 0 ? head.substring(0, headerEnd) : head;
        String[] lines = statusLineBlock.split("\r\n");
        String statusLine = lines[0];
        int code = parseStatusCode(statusLine);
        if (code == 0 || !(statusLine.startsWith("HTTP/1.") || statusLine.startsWith("HTTP/2"))) {
            String preview = statusLine.length() > 120 ? statusLine.substring(0, 120) + "…" : statusLine;
            return Result.failure("Malformed response from the proxy (status line: '" + preview.trim() + "').");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue; // tolerate junk lines rather than failing the check
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (!name.isEmpty()) headers.put(name, value);
        }
        return Result.ok(code, statusLine, headers);
    }
}
