package com.homerelay.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Idempotent, testable tracker for the resources a sharing session holds
 * (partial wake lock, Wi-Fi high-performance lock). The Android service pairs each
 * {@link #acquire} with a real framework acquisition and each {@link #release} with the
 * matching framework release; the guard makes double-acquisition, double-release and
 * cleanup-on-every-exit-path safe to reason about.
 */
public final class ResourceGuard {
    private final LinkedHashSet<String> held = new LinkedHashSet<>();

    /** @return true if this call took ownership (false when already held — caller must not re-acquire). */
    public synchronized boolean acquire(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("resource name required");
        return held.add(name);
    }

    /** @return true if this call released ownership (false when it was not held — safe no-op). */
    public synchronized boolean release(String name) {
        return name != null && held.remove(name);
    }

    /** Release everything; returns the resources that were held (in acquisition order). */
    public synchronized List<String> releaseAll() {
        List<String> released = new ArrayList<>(held);
        held.clear();
        return released;
    }

    public synchronized boolean isHeld(String name) {
        return held.contains(name);
    }

    public synchronized int heldCount() {
        return held.size();
    }

    public synchronized List<String> held() {
        return Collections.unmodifiableList(new ArrayList<>(held));
    }
}
