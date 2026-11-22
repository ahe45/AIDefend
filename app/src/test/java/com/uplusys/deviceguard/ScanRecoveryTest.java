package com.uplusys.deviceguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScanRecoveryTest {
    @Test public void resumesOnlyWithinOriginalDeadlineAndBoot() {
        ScanRecovery.Request saved = new ScanRecovery.Request(RadioScanner.Mode.ALL, "test", 1_000, 61_000, 5);
        assertTrue(saved.resumable(31_000, 5));
        assertEquals(30_000, saved.remaining(31_000));
        assertFalse(saved.resumable(61_000, 5));
        assertEquals(0, saved.remaining(70_000));
        assertFalse(saved.resumable(31_000, 6));
        assertFalse(saved.resumable(999, 5));
    }

    @Test public void unlimitedRequestRetainsUnlimitedDuration() {
        ScanRecovery.Request saved = new ScanRecovery.Request(RadioScanner.Mode.ALL, "test", 1_000, 0, 5);
        assertTrue(saved.resumable(1_000_000, 5));
        assertEquals(0, saved.remaining(1_000_000));
    }
}
