package com.uplusys.deviceguard;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class RadioMetadataTest {
    @Test public void cameraWifiRunsFirstOnlyForForegroundAutomaticPlan() {
        List<RadioScanner.Mode> requested = List.of(RadioScanner.Mode.WIFI, RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC);
        assertEquals(requested, RadioScanner.orderedPaths(requested, true, true, true));
        assertEquals(List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC, RadioScanner.Mode.WIFI),
                RadioScanner.orderedPaths(requested, true, true, false));
        assertEquals(requested, RadioScanner.orderedPaths(requested, false, true, false));
        assertEquals(RadioScanner.Mode.WIFI, requested.get(0));
    }

    @Test public void android13SsidQuotesAreRemovedWithoutChangingActualName() {
        assertEquals("My Camera", RadioScanner.unquoteWifiSsid("\"My Camera\""));
        assertEquals(" Camera ", RadioScanner.unquoteWifiSsid("\" Camera \""));
        assertEquals("\"Camera\"", RadioScanner.unquoteWifiSsid("\"\"Camera\"\""));
        assertEquals("Camera", RadioScanner.unquoteWifiSsid("Camera"));
        assertEquals("", RadioScanner.unquoteWifiSsid("\"\""));
    }

    @Test public void wifiCacheBeforeSessionOrRequestCannotBecomeCurrent() {
        assertFalse(RadioScanner.currentWifiObservation(900, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentWifiObservation(1_050, 1_000, 1_100, 1_200));
        assertTrue(RadioScanner.currentWifiObservation(1_150, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentWifiObservation(1_150, 1_000, 1_100, 40_000));
        assertFalse(RadioScanner.currentWifiObservation(3_000, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentWifiObservation(0, 0, 0, 100));
    }

    @Test public void bleCallbackFromAnOlderIntervalCannotBecomeCurrent() {
        assertFalse(RadioScanner.currentBleObservation(900, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentBleObservation(1_050, 1_000, 1_100, 1_200));
        assertTrue(RadioScanner.currentBleObservation(1_150, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentBleObservation(1_150, 1_000, 1_100, 40_000));
        assertFalse(RadioScanner.currentBleObservation(3_000, 1_000, 1_100, 1_200));
        assertFalse(RadioScanner.currentBleObservation(0, 0, 0, 100));
    }

    @Test public void appearanceRequiresCompleteLittleEndianAdvertisingField() {
        assertEquals(Integer.valueOf(0x00C2), RadioScanner.appearance(new byte[]{3, 0x19, (byte) 0xC2, 0}));
        assertEquals(Integer.valueOf(0x0941), RadioScanner.appearance(new byte[]{2, 1, 6, 3, 0x19, 0x41, 9}));
        assertNull(RadioScanner.appearance(new byte[]{3, 0x19, 0x41}));
        assertNull(RadioScanner.appearance(new byte[]{0}));
        assertNull(RadioScanner.appearance(null));
    }

    @Test public void sourceNamesAndCachedNamesRemainSeparate() {
        RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "sample", "Advertised", -60,
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), "Advertised", "Old cached name",
                RadioScanner.NameOrigin.ADVERTISEMENT, 1_200, null, null, 0x00C2, false);
        assertEquals("Advertised", signal.advertisedName());
        assertEquals("Old cached name", signal.cachedName());
        assertEquals(RadioScanner.NameOrigin.ADVERTISEMENT, signal.nameOrigin());
        assertEquals(1_200, signal.observedAtElapsed());
        assertFalse(signal.previousResult());
        assertThrows(UnsupportedOperationException.class, () -> signal.advertisedUuids().clear());
    }
}
