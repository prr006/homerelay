package com.homerelay.app.proxy;

import com.homerelay.app.Test;
import com.homerelay.app.TestRunner;

public class ProxyStatsTest {

    @Test
    public void countersAccumulate() {
        ProxyStats stats = new ProxyStats();
        stats.addBytesUp(100);
        stats.addBytesDown(2500);
        stats.incrementRequests();
        stats.incrementRequests();
        stats.incrementSuccessful();
        stats.noteClient("192.168.43.99");
        ProxyStats.Snapshot snap = stats.snapshot();
        TestRunner.assertEquals(100, snap.bytesUp, "up");
        TestRunner.assertEquals(2500, snap.bytesDown, "down");
        TestRunner.assertEquals(2600, snap.totalBytes(), "total");
        TestRunner.assertEquals(2, snap.totalRequests, "requests");
        TestRunner.assertEquals(1, snap.successfulRequests, "successes");
        TestRunner.assertEquals(1, snap.seenDevices, "devices seen");
        TestRunner.assertEquals("192.168.43.99", stats.seenDeviceIps().get(0), "device ip");
    }

    @Test
    public void connectionGauge() {
        ProxyStats stats = new ProxyStats();
        stats.connectionOpened();
        stats.connectionOpened();
        stats.connectionOpened();
        stats.connectionClosed();
        TestRunner.assertEquals(2, stats.snapshot().activeConnections, "two active");
    }

    @Test
    public void resetClears() {
        ProxyStats stats = new ProxyStats();
        stats.addBytesUp(10);
        stats.noteClient("1.2.3.4");
        stats.resetSession();
        TestRunner.assertEquals(0, stats.snapshot().bytesUp, "bytes reset");
        TestRunner.assertEquals(0, stats.seenDeviceCount(), "clients reset");
    }
}
