package com.homerelay.app.util;

import com.homerelay.app.Test;

import java.util.List;

/** Resource cleanup semantics for the host wake/Wi-Fi locks (screen-off hosting). */
public final class ResourceGuardTest {

    @Test
    public void acquireIsIdempotentPerName() {
        ResourceGuard g = new ResourceGuard();
        assertTrue(g.acquire("partial_wake_lock"));
        assertFalse("second acquire of the same resource must not re-take ownership", g.acquire("partial_wake_lock"));
        assertTrue(g.isHeld("partial_wake_lock"));
        assertEquals(1, g.heldCount());
    }

    @Test
    public void distinctResourcesAcquireIndependently() {
        ResourceGuard g = new ResourceGuard();
        assertTrue(g.acquire("partial_wake_lock"));
        assertTrue(g.acquire("wifi_high_perf_lock"));
        assertEquals(2, g.heldCount());
        assertEquals("partial_wake_lock, wifi_high_perf_lock", String.join(", ", g.held()));
    }

    @Test
    public void releaseIsExactAndIdempotent() {
        ResourceGuard g = new ResourceGuard();
        g.acquire("a");
        g.acquire("b");
        assertTrue(g.release("a"));
        assertFalse("double release must be a safe no-op", g.release("a"));
        assertTrue(g.release("b"));
        assertEquals(0, g.heldCount());
        assertFalse(g.isHeld("a"));
        assertFalse(g.isHeld("b"));
    }

    @Test
    public void releaseAllEmptiesEverything() {
        ResourceGuard g = new ResourceGuard();
        g.acquire("partial_wake_lock");
        g.acquire("wifi_high_perf_lock");
        List<String> released = g.releaseAll();
        assertEquals("partial_wake_lock, wifi_high_perf_lock", String.join(", ", released));
        assertEquals(0, g.heldCount());
        assertTrue(g.held().isEmpty());
    }

    @Test
    public void releaseAllOnEmptyIsEmpty() {
        ResourceGuard g = new ResourceGuard();
        assertTrue(g.releaseAll().isEmpty());
    }

    @Test
    public void nullAndEmptyNamesRejected() {
        ResourceGuard g = new ResourceGuard();
        boolean threw = false;
        try {
            g.acquire(null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
        threw = false;
        try {
            g.acquire("");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
        assertFalse("release(null) must be a safe no-op", g.release(null));
    }

    @Test
    public void stopPathReleasesAllResources() {
        // Simulates the service's fail/stop/stopSelfAndClean exit paths: everything
        // acquired for the session must be gone afterwards, whatever order they took.
        ResourceGuard g = new ResourceGuard();
        g.acquire("partial_wake_lock");
        g.acquire("wifi_high_perf_lock");
        assertEquals(2, g.releaseAll().size());
        assertEquals(0, g.heldCount());
        // A second exit path running releaseAll() afterwards must be harmless.
        assertEquals(0, g.releaseAll().size());
    }

    private static void assertTrue(String msg, boolean v) { if (!v) throw new AssertionError(msg); }
    private static void assertTrue(boolean v) { if (!v) throw new AssertionError("expected true"); }
    private static void assertFalse(String msg, boolean v) { if (v) throw new AssertionError(msg); }
    private static void assertFalse(boolean v) { if (v) throw new AssertionError("expected false"); }
    private static void assertEquals(Object e, Object a) {
        if (e == null ? a != null : !e.equals(a)) throw new AssertionError("expected " + e + " but was " + a);
    }
}
