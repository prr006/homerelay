package com.homerelay.app.util;

import com.homerelay.app.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the plain-HTTP diagnostic's socket probe. Uses a local mock HTTP proxy
 * on 127.0.0.1 — no network, no cleartext policy involved (this is the JVM test harness).
 */
public final class ProxyHttpProbeTest {

    /** Records the request bytes received by the mock proxy. */
    static final class MockProxy implements AutoCloseable {
        final ServerSocket server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final String response;          // raw response bytes as ISO_8859_1 string, or null to hang
        final CountDownLatch served = new CountDownLatch(1);

        MockProxy(String response) throws IOException {
            this.server = new ServerSocket(0);
            this.response = response;
            Thread t = new Thread(() -> {
                try {
                    Socket s = server.accept();
                    s.setSoTimeout(4000);
                    StringBuilder req = new StringBuilder();
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[1024];
                    int total = 0;
                    // Read until we see end-of-headers
                    while (total < 8192 && req.indexOf("\r\n\r\n") < 0) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        req.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                        total += n;
                    }
                    requests.add(req.toString());
                    if (response != null) {
                        OutputStream out = s.getOutputStream();
                        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        s.close();
                    } else {
                        Thread.sleep(1500); // hang — simulates a silent proxy
                        s.close();
                    }
                    served.countDown();
                } catch (Exception ignored) {
                    served.countDown();
                }
            }, "mock-proxy");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() throws IOException { server.close(); }
    }

    @Test
    public void sendsAbsoluteFormGetWithHostAndConnectionClose() throws Exception {
        try (MockProxy proxy = new MockProxy("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n")) {
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(),
                "http://connectivitycheck.gstatic.com/generate_204",
                "connectivitycheck.gstatic.com",
                2000, 2000);
            assertTrue("probe should succeed, got " + r.error, r.success);
            assertEquals(204, r.statusCode);
            proxy.served.await(2, TimeUnit.SECONDS);
            String req = proxy.requests.get(0);
            assertTrue("absolute-form request line, got: " + req,
                req.startsWith("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\n"));
            assertTrue("Host header present", req.contains("\r\nHost: connectivitycheck.gstatic.com\r\n"));
            assertTrue("Connection: close present", req.contains("\r\nConnection: close\r\n"));
        }
    }

    @Test
    public void valid200ResponseWithBodyParsed() throws Exception {
        String body = "<html><body>ok</body></html>";
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length() + "\r\nX-Test: yes\r\n\r\n" + body;
        try (MockProxy proxy = new MockProxy(resp)) {
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(), "http://example.com/", "example.com", 2000, 2000);
            assertTrue("probe success, got " + r.error, r.success);
            assertEquals(200, r.statusCode);
            assertEquals("text/html", r.headers.get("content-type"));
            assertEquals("yes", r.headers.get("x-test"));
            assertEquals("HTTP/1.1 200 OK", r.statusLine);
        }
    }

    @Test
    public void connectionRefusedIsReportedNotThrown() throws Exception {
        int unused;
        try (ServerSocket s = new ServerSocket(0)) {
            unused = s.getLocalPort();
        } // closed → nothing listening
        ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
            "127.0.0.1", unused, "http://example.com/", "example.com", 1000, 1000);
        assertFalse("probe must fail", r.success);
        assertNotNull("error message present", r.error);
        assertTrue("mentions refusal, got: " + r.error,
            r.error.toLowerCase().contains("refused"));
    }

    @Test
    public void malformedStatusLineIsRejected() throws Exception {
        try (MockProxy proxy = new MockProxy("this is not http\r\n\r\n")) {
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(), "http://example.com/", "example.com", 2000, 2000);
            assertFalse("must fail", r.success);
            assertTrue("malformed reported, got: " + r.error, r.error.contains("Malformed"));
        }
    }

    @Test
    public void garbageCodeIsRejected() throws Exception {
        try (MockProxy proxy = new MockProxy("HTTP/1.1 999 No\r\n\r\n")) {
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(), "http://example.com/", "example.com", 2000, 2000);
            assertFalse("must fail", r.success);
        }
    }

    @Test
    public void emptyResponseIsRejected() throws Exception {
        try (MockProxy proxy = new MockProxy("")) { // responds, closes immediately
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(), "http://example.com/", "example.com", 2000, 2000);
            assertFalse("must fail", r.success);
            assertTrue("empty reported, got: " + r.error, r.error.toLowerCase().contains("empty"));
        }
    }

    @Test
    public void silentProxyTimesOut() throws Exception {
        try (MockProxy proxy = new MockProxy(null)) { // accepts, never answers
            ProxyHttpProbe.Result r = ProxyHttpProbe.probeGet(
                "127.0.0.1", proxy.port(), "http://example.com/", "example.com", 1000, 500);
            assertFalse("must fail", r.success);
            assertTrue("timeout reported, got: " + r.error,
                r.error.toLowerCase().contains("timed out"));
        }
    }

    @Test
    public void parseResponseHandlesHeadersCaseInsensitively() {
        byte[] raw = "HTTP/1.1 204 No Content\r\nX-CuStOm: Value\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        ProxyHttpProbe.Result r = ProxyHttpProbe.parseResponse(raw);
        assertTrue("parse ok", r.success);
        assertEquals("Value", r.headers.get("x-custom"));
    }

    @Test
    public void buildRequestExactBytes() {
        String req = ProxyHttpProbe.buildRequest("http://connectivitycheck.gstatic.com/generate_204",
            "connectivitycheck.gstatic.com");
        assertEquals("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\n"
            + "Host: connectivitycheck.gstatic.com\r\n"
            + "User-Agent: HomeRelay-Diagnostics/1.0\r\n"
            + "Accept: */*\r\n"
            + "Connection: close\r\n"
            + "\r\n", req);
    }

    @Test
    public void invalidArgumentsAreFailures() {
        assertFalse(ProxyHttpProbe.probeGet(null, 8080, "http://x/", "x", 100, 100).success);
        assertFalse(ProxyHttpProbe.probeGet("127.0.0.1", 0, "http://x/", "x", 100, 100).success);
        assertFalse(ProxyHttpProbe.probeGet("127.0.0.1", 99999, "http://x/", "x", 100, 100).success);
    }

    private static void assertTrue(String msg, boolean v) {
        if (!v) throw new AssertionError(msg);
    }

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertFalse(String msg, boolean v) {
        if (v) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean v) {
        if (v) throw new AssertionError("expected false");
    }

    private static void assertNotNull(String msg, Object v) {
        if (v == null) throw new AssertionError(msg);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
