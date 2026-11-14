package com.homerelay.app.util;

import com.homerelay.app.Test;

/** Regression tests for the session-generation guard used by the sharing service. */
public final class SessionGuardTest {

    @Test
    public void freshGuardHasGenerationZeroAndIsNotCurrent() {
        SessionGuard g = new SessionGuard();
        assertEquals(0L, g.current());
        assertFalse(g.isCurrent(0L + 1)); // nobody holds a session yet
    }

    @Test
    public void bumpCreatesDistinctLiveGenerations() {
        SessionGuard g = new SessionGuard();
        long a = g.bump();
        long b = g.bump();
        assertTrue(a != b);
        assertFalse(g.isCurrent(a));
        assertTrue(g.isCurrent(b));
    }

    @Test
    public void stopInvalidatesOutstandingSession() {
        SessionGuard g = new SessionGuard();
        long s = g.bump();
        assertTrue(g.isCurrent(s));
        g.invalidate();
        assertFalse(g.isCurrent(s));
    }

    @Test
    public void staleCallbackCannotTearDownNewerSession() {
        // Mirrors the v1.1.1 bug class: an old stop/fail path must not touch the new session.
        SessionGuard g = new SessionGuard();
        long first = g.bump();
        g.invalidate();          // user pressed Stop
        long second = g.bump();  // user pressed Start again
        assertFalse(g.isCurrent(first));
        assertTrue(g.isCurrent(second));
    }

    @Test
    public void repeatedStartStopCyclesNeverReanimateOldTokens() {
        SessionGuard g = new SessionGuard();
        long first = g.bump();
        for (int i = 0; i < 50; i++) {
            g.invalidate();
            g.bump();
        }
        assertFalse(g.isCurrent(first));
        assertTrue(g.isCurrent(g.current()));
    }

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean v) {
        if (v) throw new AssertionError("expected false");
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " but was " + actual);
    }
}
