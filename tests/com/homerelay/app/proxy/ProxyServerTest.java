package com.homerelay.app.proxy;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * JVM integration tests for the proxy: a real origin server, a real proxy, and real
 * sockets on 127.0.0.1. These verify the actual data path — no mocks of the network.
 */
public class ProxyServerTest {

    /* ---------- test plumbing ---------- */

    static final class TestConnector implements ProxyServer.OutboundConnector {
        @Override
        public Socket open(String host, int port, int connectTimeoutMs) throws IOException {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress(host, port), connectTimeoutMs);
            return s;
        }
    }

    static final class TestDns implements ProxyServer.DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if ("fail.invalid".equals(host)) throw new UnknownHostException(host);
            try {
                return new InetAddress[]{InetAddress.getByName(host)};
            } catch (UnknownHostException e) {
                // Tests refer to the local origin via a fixed alias.
                if ("test.origin".equals(host)) return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                throw e;
            }
        }
    }

    /** Single-shot origin server that records the request head and replies with a fixed body. */
    static final class OriginServer implements Runnable {
        final ServerSocket server;
        final String body;
        final String contentType;
        final ConcurrentLinkedQueue<String> capturedHeads = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<byte[]> capturedBodies = new ConcurrentLinkedQueue<>();
        volatile boolean running = true;

        OriginServer(int port, String body, String contentType) throws IOException {
            server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            this.body = body;
            this.contentType = contentType;
            new Thread(this, "origin").start();
        }

        int port() { return server.getLocalPort(); }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket s = server.accept();
                    handle(s);
                } catch (IOException e) {
                    if (running) e.printStackTrace(System.out);
                    return;
                }
            }
        }

        private void handle(Socket s) {
            try {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                // Byte-at-a-time head scan: stop exactly at CRLFCRLF so body bytes stay on the stream.
                int matched = 0;
                int[] sequence = {0x0D, 0x0A, 0x0D, 0x0A};
                for (;;) {
                    int b = in.read();
                    if (b == -1) break;
                    head.write(b);
                    if (b == sequence[matched]) {
                        matched++;
                        if (matched == 4) break;
                    } else {
                        matched = (b == 0x0D) ? 1 : 0;
                    }
                }
                String headText = head.toString(StandardCharsets.ISO_8859_1);
                capturedHeads.add(headText);
                // Read body if Content-Length present.
                int contentLength = 0;
                for (String line : headText.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                byte[] bodyBytes = new byte[Math.max(0, contentLength)];
                int off = 0;
                while (off < bodyBytes.length) {
                    int r = in.read(bodyBytes, off, bodyBytes.length - off);
                    if (r == -1) break;
                    off += r;
                }
                if (bodyBytes.length > 0) capturedBodies.add(bodyBytes);

                byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
                String responseHead = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n"
                        + "Content-Length: " + responseBody.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = s.getOutputStream();
                out.write(responseHead.getBytes(StandardCharsets.ISO_8859_1));
                out.write(responseBody);
                out.flush();
                s.close();
            } catch (Exception e) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        void stop() {
            running = false;
            try { server.close(); } catch (IOException ignored) {}
        }
    }

    /** Echo server for CONNECT tunnel tests. */
    static final class EchoServer implements Runnable {
        final ServerSocket server;
        volatile boolean running = true;

        EchoServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            new Thread(this, "echo").start();
        }

        int port() { return server.getLocalPort(); }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> {
                        try {
                            s.setSoTimeout(5000);
                            InputStream in = s.getInputStream();
                            OutputStream out = s.getOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                out.flush();
                            }
                        } catch (IOException ignored) {
                        } finally {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    }, "echo-conn").start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        void stop() {
            running = false;
            try { server.close(); } catch (IOException ignored) {}
        }
    }

    private ProxyServer newRunningProxy(String bindHost) throws IOException {
        ProxyServer.Config config = new ProxyServer.Config();
        config.pacHost = bindHost;
        config.pacPort = 0; // filled by start()
        config.viaHeader = "HomeRelay-Test";
        config.connectTimeoutMs = 2000;
        config.headReadTimeoutMs = 2000;
        config.originReadTimeoutMs = 2000;
        ProxyServer proxy = new ProxyServer(config, new TestConnector(), new TestDns(), null);
        proxy.start(List.of(bindHost), 0); // ephemeral port
        return proxy;
    }

    private int proxyPort(ProxyServer proxy) {
        return proxy.boundPort();
    }

    /* ---------- tests ---------- */

    @Test
    public void plainHttpRequestFlowsThroughProxy() throws Exception {
        OriginServer origin = new OriginServer(0, "hello-origin-payload", "text/plain");
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            OutputStream out = client.getOutputStream();
            String req = "GET http://test.origin:" + origin.port() + "/hello?q=1 HTTP/1.1\r\n"
                    + "Host: test.origin:" + origin.port() + "\r\n"
                    + "Accept: text/plain\r\n"
                    + "Proxy-Authorization: Basic c2VjcmV0\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
            String text = response.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(text.contains("hello-origin-payload"), "origin body relayed");
            TestRunner.assertTrue(text.startsWith("HTTP/1.1 200"), "status relayed");
            String captured = origin.capturedHeads.peek();
            TestRunner.assertNotNull(captured, "origin saw the request");
            TestRunner.assertTrue(captured.contains("GET /hello?q=1 HTTP/1.1"), "origin-form path");
            TestRunner.assertFalse(captured.toLowerCase().contains("proxy-authorization"),
                    "proxy credentials stripped before forwarding");
            TestRunner.assertTrue(captured.toLowerCase().contains("via: homerelay-test"), "via header added");
            ProxyStats.Snapshot snap = proxy.stats().snapshot();
            TestRunner.assertTrue(snap.bytesDown > 0, "download counted");
            TestRunner.assertTrue(snap.bytesUp > 0, "upload counted");
            TestRunner.assertEquals(1, snap.totalRequests, "one request counted");
            TestRunner.assertEquals(1, snap.successfulRequests, "one success counted");
            TestRunner.assertEquals(0, snap.failedRequests, "no failures");
            TestRunner.assertEquals(1, snap.seenDevices, "device tracked");
            client.close();
        } finally {
            proxy.stop();
            origin.stop();
        }
    }

    @Test
    public void connectTunnelIsBidirectional() throws Exception {
        EchoServer echo = new EchoServer();
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();
            String req = "CONNECT 127.0.0.1:" + echo.port() + " HTTP/1.1\r\nHost: 127.0.0.1:" + echo.port() + "\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // Read the established response head.
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            int matched = 0;
            int[] seq = {0x0D, 0x0A, 0x0D, 0x0A};
            while (true) {
                int b = in.read();
                TestRunner.assertTrue(b != -1, "tunnel establishment response present");
                headBytes.write(b);
                if (b == seq[matched]) {
                    matched++;
                    if (matched == 4) break;
                } else {
                    matched = (b == 0x0D) ? 1 : 0;
                }
            }
            String headText = headBytes.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(headText.startsWith("HTTP/1.1 200"), "tunnel established");
            out.write("ping-through-tunnel".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] echoOut = new byte[19];
            int off = 0;
            while (off < echoOut.length) {
                int r = in.read(echoOut, off, echoOut.length - off);
                if (r == -1) break;
                off += r;
            }
            TestRunner.assertEquals("ping-through-tunnel",
                    new String(echoOut, 0, off, StandardCharsets.UTF_8), "echo through CONNECT tunnel");
            ProxyStats.Snapshot snap = proxy.stats().snapshot();
            TestRunner.assertEquals(1, snap.connectTunnels, "tunnel counted");
            TestRunner.assertEquals(1, snap.successfulRequests, "tunnel counts as success");
            TestRunner.assertTrue(snap.bytesDown >= 19 && snap.bytesUp >= 19, "bidirectional bytes counted");
            client.close();
        } finally {
            proxy.stop();
            echo.stop();
        }
    }

    @Test
    public void dnsFailureYields502() throws Exception {
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            OutputStream out = client.getOutputStream();
            String req = "GET http://fail.invalid/x HTTP/1.1\r\nHost: fail.invalid\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
            String text = response.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(text.startsWith("HTTP/1.1 502"), "502 on dns failure");
            client.close();
        } finally {
            proxy.stop();
        }
    }

    @Test
    public void pacAndStatusEndpointsServedLocally() throws Exception {
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            for (String[] check : new String[][]{
                    {"/proxy.pac", "FindProxyForURL"},
                    {"/status.json", "HomeRelay"},
                    {"/", "HomeRelay"},
            }) {
                Socket client = new Socket("127.0.0.1", port);
                client.setSoTimeout(3000);
                OutputStream out = client.getOutputStream();
                String req = "GET http://127.0.0.1:" + port + check[0] + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
                String text = response.toString(StandardCharsets.ISO_8859_1);
                TestRunner.assertTrue(text.startsWith("HTTP/1.1 200"), check[0] + " returns 200");
                TestRunner.assertTrue(text.contains(check[1]), check[0] + " contains " + check[1]);
                client.close();
            }
        } finally {
            proxy.stop();
        }
    }

    @Test
    public void headAndBodyInSinglePacketAreHandled() throws Exception {
        OriginServer origin = new OriginServer(0, "post-received", "text/plain");
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            String body = "name=homerelay&value=42";
            String req = "POST http://test.origin:" + origin.port() + "/submit HTTP/1.1\r\n"
                    + "Host: test.origin:" + origin.port() + "\r\n"
                    + "Content-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "\r\n" + body;
            // Write the ENTIRE request in one shot; the pushback buffer must preserve the body.
            client.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
            String text = response.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(text.startsWith("HTTP/1.1 200"), "200 from origin");
            byte[] captured = origin.capturedBodies.peek();
            TestRunner.assertNotNull(captured, "origin received a body");
            TestRunner.assertEquals(body, new String(captured, StandardCharsets.US_ASCII), "body intact");
            client.close();
        } finally {
            proxy.stop();
            origin.stop();
        }
    }

    @Test
    public void chunkedRequestBodyIsDecoded() throws Exception {
        OriginServer origin = new OriginServer(0, "chunked-ok", "text/plain");
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            String chunk1 = "Wiki", chunk2 = "pedia";
            String req = "POST http://test.origin:" + origin.port() + "/chunk HTTP/1.1\r\n"
                    + "Host: test.origin:" + origin.port() + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "\r\n"
                    + Integer.toHexString(chunk1.length()) + "\r\n" + chunk1 + "\r\n"
                    + Integer.toHexString(chunk2.length()) + "\r\n" + chunk2 + "\r\n"
                    + "0\r\n\r\n";
            client.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
            String text = response.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(text.startsWith("HTTP/1.1 200"), "200 from origin");
            byte[] captured = origin.capturedBodies.peek();
            TestRunner.assertNotNull(captured, "origin received chunked body decoded");
            TestRunner.assertEquals("Wikipedia", new String(captured, StandardCharsets.US_ASCII), "chunks reassembled");
            client.close();
        } finally {
            proxy.stop();
            origin.stop();
        }
    }

    @Test
    public void originDownYields502NotSilence() throws Exception {
        ProxyServer proxy = newRunningProxy("127.0.0.1");
        try {
            int port = proxyPort(proxy);
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(3000);
            OutputStream out = client.getOutputStream();
            // Port 1 is almost certainly closed; connection refused expected.
            String req = "GET http://127.0.0.1:1/nope HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = client.getInputStream().read(buf)) != -1) response.write(buf, 0, n);
            String text = response.toString(StandardCharsets.ISO_8859_1);
            TestRunner.assertTrue(text.startsWith("HTTP/1.1 502"), "502 when origin unreachable");
            client.close();
        } finally {
            proxy.stop();
        }
    }
}
