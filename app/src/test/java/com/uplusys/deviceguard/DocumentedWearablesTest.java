package com.uplusys.deviceguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class DocumentedWearablesTest {
    private static final SavedTargets.Snapshot DEFAULT_TARGETS = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of());

    @Test public void polarWatchRequiresRawAdvertisingNameAndValidId() {
        String model = "Polar Grit X2 Pro";
        assertTrue(matches(model, bleName(model + " 1234567")));
        assertTrue(matches(model, bleName(model + " 1234567C")));
        assertFalse(matches(model, bleName(model + " 1234567D")));
        assertFalse(matches(model, bleName("Polar Grit X2 1234567C")));
        assertFalse(matches(model, signal(RadioScanner.Mode.BLE, model + " 1234567C", RadioScanner.NameOrigin.CACHE)));
        assertFalse(matches(model, signal(RadioScanner.Mode.CLASSIC, model + " 1234567C", RadioScanner.NameOrigin.CLASSIC_RESPONSE)));
    }

    @Test public void earbudsMatchOnlyTheExactFreshBluetoothPairingName() {
        String model = "Jabra Elite 8 Active";
        assertTrue(matches(model, bleName("jabra elite 8 active")));
        assertTrue(matches(model, signal(RadioScanner.Mode.CLASSIC, model, RadioScanner.NameOrigin.CLASSIC_RESPONSE)));
        assertFalse(matches(model, bleName(model + " Gen 2")));
        assertTrue(matches(model + " Gen 2", bleName(model + " Gen 2")));
        assertFalse(matches(model, signal(RadioScanner.Mode.CLASSIC, model, RadioScanner.NameOrigin.CACHE)));
    }

    private static boolean matches(String model, RadioScanner.Signal signal) {
        return ScanService.match(signal, DEFAULT_TARGETS).stream()
                .anyMatch(match -> match.ruleId().equals("builtin/" + model));
    }

    private static RadioScanner.Signal bleName(String name) {
        return signal(RadioScanner.Mode.BLE, name, RadioScanner.NameOrigin.ADVERTISEMENT);
    }

    private static RadioScanner.Signal signal(RadioScanner.Mode mode, String name, RadioScanner.NameOrigin origin) {
        return new RadioScanner.Signal(mode, "01:23:45:67:89:AB", name, -60, Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), origin == RadioScanner.NameOrigin.ADVERTISEMENT ? name : null,
                origin == RadioScanner.NameOrigin.CACHE ? name : null, origin, 1L, null, null, null, false);
    }
}
