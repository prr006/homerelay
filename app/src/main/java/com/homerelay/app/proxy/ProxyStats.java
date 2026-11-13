package com.homerelay.app.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-memory accounting for everything the proxy does.
 * All counters are real measurements (bytes actually relayed); when a value
 * cannot be measured it is simply not exposed — nothing here is simulated.
 */
public final class ProxyStats {
    /** Keep at most this many recently-seen client addresses for display. */
    private static final int MAX_TRACKED_CLIENTS = 64;
    /** A client is "seen" for this long after its last request. */
    private static final long CLIENT_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    private final AtomicLong bytesUp = new AtomicLong();      // client -> origin
    private final AtomicLong bytesDown = new AtomicLong();    // origin -> client
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong connectTunnels = new AtomicLong();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong activeConnections = new AtomicLong();
    private final Object clientLock = new Object();
    private final LinkedHashMap<String, Long> seenClients = new LinkedHashMap<>();

    public void addBytesUp(long n) { if (n > 0) bytesUp.addAndGet(n); }
    public void addBytesDown(long n) { if (n > 0) bytesDown.addAndGet(n); }
    public void incrementRequests() { totalRequests.incrementAndGet(); }
    public void incrementConnectTunnels() { connectTunnels.incrementAndGet(); }
    public void incrementSuccessful() { successfulRequests.incrementAndGet(); }
    public void incrementFailed() { failedRequests.incrementAndGet(); }
    public void connectionOpened() { activeConnections.incrementAndGet(); }
    public void connectionClosed() { activeConnections.decrementAndGet(); }

    public void noteClient(String ip) {
        if (ip == null) return;
        synchronized (clientLock) {
            seenClients.put(ip, System.currentTimeMillis());
            while (seenClients.size() > MAX_TRACKED_CLIENTS) {
                String eldest = seenClients.keySet().iterator().next();
                seenClients.remove(eldest);
            }
        }
    }

    private void pruneClientsLocked(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> e : seenClients.entrySet()) {
            if (now - e.getValue() > CLIENT_TTL_MS) expired.add(e.getKey());
        }
        for (String k : expired) seenClients.remove(k);
    }

    public int seenDeviceCount() {
        synchronized (clientLock) {
            pruneClientsLocked(System.currentTimeMillis());
            return seenClients.size();
        }
    }

    public List<String> seenDeviceIps() {
        synchronized (clientLock) {
            pruneClientsLocked(System.currentTimeMillis());
            List<String> list = new ArrayList<>(seenClients.keySet());
            return Collections.unmodifiableList(list);
        }
    }

    /** Immutable snapshot for UI/diagnostics. */
    public Snapshot snapshot() {
        return new Snapshot(
                bytesUp.get(), bytesDown.get(),
                totalRequests.get(), connectTunnels.get(),
                successfulRequests.get(), failedRequests.get(),
                Math.max(0, activeConnections.get()),
                seenDeviceCount());
    }

    public void resetSession() {
        bytesUp.set(0); bytesDown.set(0);
        totalRequests.set(0); connectTunnels.set(0);
        successfulRequests.set(0); failedRequests.set(0);
        synchronized (clientLock) { seenClients.clear(); }
    }

    public static final class Snapshot {
        public final long bytesUp;
        public final long bytesDown;
        public final long totalRequests;
        public final long connectTunnels;
        public final long successfulRequests;
        public final long failedRequests;
        public final long activeConnections;
        public final int seenDevices;

        Snapshot(long bytesUp, long bytesDown, long totalRequests, long connectTunnels,
                 long successfulRequests, long failedRequests, long activeConnections, int seenDevices) {
            this.bytesUp = bytesUp;
            this.bytesDown = bytesDown;
            this.totalRequests = totalRequests;
            this.connectTunnels = connectTunnels;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
            this.activeConnections = activeConnections;
            this.seenDevices = seenDevices;
        }

        public long totalBytes() { return bytesUp + bytesDown; }
    }
}
