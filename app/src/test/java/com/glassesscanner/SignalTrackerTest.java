package com.glassesscanner;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalTrackerTest {
    @Test public void stableAdvertisementsKeepReceiptFresh() {
        SignalTracker tracker = new SignalTracker();
        for (long time = 0; time <= 10_000; time += 20) tracker.add(-65, time);
        var reading = tracker.reading(10_000);
        assertEquals(10_000, reading.lastSeen());
        assertEquals(-65, reading.smoothed(), 0.01);
        assertTrue(reading.samples() > 4);
        assertTrue(reading.samples() <= 40);
        assertEquals(0, reading.change(), 0.01);
    }

    @Test public void trendFollowsSustainedChangeAndExpiresAfterSilence() {
        SignalTracker tracker = new SignalTracker();
        tracker.add(-85, 0);
        tracker.add(-80, 1000);
        tracker.add(-70, 2000);
        tracker.add(-60, 3000);
        assertTrue(tracker.reading(3000).change() >= 3);
        assertEquals(0, tracker.reading(10_000).change(), 0.01);
        tracker.add(-95, 11_000);
        assertEquals(-95, tracker.reading(11_000).smoothed(), 0.01);
        assertEquals(1, tracker.reading(11_000).samples());
    }

    @Test public void unknownRssiNeverBecomesAFalseProximityReading() {
        SignalTracker tracker = new SignalTracker();
        tracker.add(null, 0);
        tracker.add(127, 1000);
        assertNull(tracker.reading(1000));
        tracker.add(-60, 2000);
        tracker.add(null, 5000);
        assertEquals(2000, tracker.reading(5000).lastSeen());
    }
}
