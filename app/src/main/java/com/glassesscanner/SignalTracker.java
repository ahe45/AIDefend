package com.glassesscanner;

import java.util.ArrayDeque;

/** Recent samples only; elapsed realtime is supplied by the scanner. */
final class SignalTracker {
    static final long STALE_MS = 6_000;
    record Reading(int latest, double smoothed, double change, long lastSeen, int samples) {}
    private record Sample(long time, double value) {}
    private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    private int latest;
    private double smoothed;
    private long lastSeen;
    private boolean initialized;

    void add(Integer rssi, long now) {
        if (rssi == null || rssi < -127 || rssi > 20 || rssi == 127) return;
        if (initialized && !recent.isEmpty() && now - recent.peekLast().time() < 100) {
            lastSeen = now;
            return; // Filtered and unfiltered callbacks can report the same advertisement.
        }
        if (!initialized || now - lastSeen > STALE_MS) {
            recent.clear();
            smoothed = rssi;
        } else smoothed += 0.3 * (rssi - smoothed);
        initialized = true;
        latest = rssi;
        lastSeen = now;
        recent.addLast(new Sample(now, smoothed));
        while (recent.size() > 40 || (!recent.isEmpty() && now - recent.peekFirst().time() > 8_000)) recent.removeFirst();
    }

    Reading reading(long now) {
        if (!initialized) return null;
        double change = recent.size() >= 4 && now - lastSeen <= STALE_MS
                && lastSeen - recent.peekFirst().time() >= 1_000
                ? smoothed - recent.peekFirst().value() : 0;
        return new Reading(latest, smoothed, change, lastSeen, recent.size());
    }
}
