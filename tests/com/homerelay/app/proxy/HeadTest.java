package com.homerelay.app.proxy;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;
import java.util.List;

public class HeadTest {

    @Test
    public void parsesRequestLineAndHeaders() {
        Head h = Head.parse("GET http://example.com/path HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\n\r\n");
        TestRunner.assertEquals("GET http://example.com/path HTTP/1.1", h.startLine(), "start line");
        TestRunner.assertEquals("example.com", h.get("host"), "host case-insensitive");
        TestRunner.assertEquals("*/*", h.get("Accept"), "accept");
    }

    @Test
    public void toleratesLfOnly() {
        Head h = Head.parse("GET / HTTP/1.1\nHost: a.b\n\n");
        TestRunner.assertEquals("a.b", h.get("Host"), "lf parsed");
    }

    @Test
    public void joinsObsFoldContinuations() {
        Head h = Head.parse("GET / HTTP/1.0\r\nX-Long: one\r\n\ttwo\r\nHost: h\r\n\r\n");
        TestRunner.assertEquals("one two", h.get("X-Long"), "obs-fold joined");
    }

    @Test
    public void multiValuedHeadersAreKept() {
        Head h = Head.parse("GET / HTTP/1.1\r\nAccept: a\r\nAccept: b\r\n\r\n");
        List<String> all = h.getAll("accept");
        TestRunner.assertEquals(2, all.size(), "two values");
        TestRunner.assertEquals("a", all.get(0), "first value");
    }

    @Test
    public void stripsHopByHopIncludingNominated() {
        Head h = Head.parse("GET / HTTP/1.1\r\nConnection: X-Hop, close\r\nX-Hop: secret\r\nKeep-Alive: timeout=5\r\nProxy-Authorization: Basic abc\r\nHost: h\r\n\r\n");
        h.stripHopByHop();
        TestRunner.assertFalse(h.has("X-Hop"), "nominated header stripped");
        TestRunner.assertFalse(h.has("Connection"), "connection stripped");
        TestRunner.assertFalse(h.has("Keep-Alive"), "keep-alive stripped");
        TestRunner.assertFalse(h.has("Proxy-Authorization"), "proxy-auth stripped");
        TestRunner.assertEquals("h", h.get("Host"), "host kept");
    }

    @Test
    public void setAndAddWork() {
        Head h = Head.parse("GET / HTTP/1.1\r\nX-A: one\r\n\r\n");
        h.set("X-A", "two");
        TestRunner.assertEquals("two", h.get("X-A"), "set replaces");
        h.add("X-A", "three");
        TestRunner.assertEquals(2, h.getAll("X-A").size(), "add appends");
        h.set("X-B", "1");
        TestRunner.assertEquals("1", h.get("X-B"), "set creates");
    }

    @Test
    public void serializesWithCrlfTerminator() {
        Head h = Head.parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n");
        h.set("Via", "HomeRelay");
        String out = new String(h.toBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        TestRunner.assertTrue(out.contains("Via: HomeRelay\r\n"), "via serialized");
        TestRunner.assertTrue(out.endsWith("\r\n\r\n"), "ends with blank line");
    }

    @Test
    public void rejectsBadTokenHeaderNames() {
        Head h = Head.parse("GET / HTTP/1.1\r\nBad Header: x\r\nHost: ok\r\n\r\n");
        TestRunner.assertFalse(h.has("Bad Header"), "space in name rejected");
        TestRunner.assertEquals("ok", h.get("Host"), "good header survives");
    }
}
