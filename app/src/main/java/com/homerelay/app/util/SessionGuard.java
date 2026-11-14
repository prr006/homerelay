package com.homerelay.app.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generation-token guard for a single foreground service that owns a session
 * (hotspot / P2P group / proxy). Framework-free and testable.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link #bump()} starts a new session and returns its token.
 *   <li>{@link #isCurrent(long)} tells callbacks whether they belong to the live session.
 *   <li>{@link #invalidate()} retires the current session without starting a new one.
 * </ul>
 * A callback that finds its token stale must not touch any shared state — in particular
 * it must never stop the proxy or clear AppState, which may belong to a newer session.
 */
public final class SessionGuard {
    private final AtomicLong counter = new AtomicLong(0L);

    /** Bump the generation and return the new session's token. */
    public long bump() { return counter.incrementAndGet(); }

    /** Retire the current generation (any outstanding token becomes stale). */
    public void invalidate() { counter.incrementAndGet(); }

    /** True iff {@code token} is still the live generation. */
    public boolean isCurrent(long token) { return counter.get() == token; }

    public long current() { return counter.get(); }
}
