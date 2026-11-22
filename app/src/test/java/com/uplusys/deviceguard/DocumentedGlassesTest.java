package com.uplusys.deviceguard;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DocumentedGlassesTest {
    private static final String REPL_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    private static final SessionConfig CONFIG = SessionConfig.create(Set.of(DeviceCategory.GLASSES),
            new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of()), RadioScanner.Mode.COMBINED, false);

    @Test public void ar99RequiresTheProjectHeaderAndManufacturerPayloadLength() {
        String label = "Xingyi AR99";
        String payload = "3939" + "00".repeat(16);
        assertEquals(1, matches(label, ble(null, Set.of(), Set.of(), Map.of(0x5241, payload))).size());
        assertEquals(1, matches(label, ble(null, Set.of(), Set.of(), Map.of(0x7261, payload))).size());
        assertTrue(matches(label, ble(null, Set.of(), Set.of(), Map.of(0x5241, "3939"))).isEmpty());
        assertTrue(matches(label, ble(null, Set.of(), Set.of(),
                Map.of(0x5241, "3938" + "00".repeat(16)))).isEmpty());
    }

    @Test public void monocleRequiresTheAdvertisedNameAndAdvertisedServiceUuid() {
        String label = "Brilliant Labs Monocle";
        List<DeviceMatcher.Match> matches = matches(label, ble("monocle", Set.of(REPL_UUID), Set.of(), Map.of()));
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).evidence().size());
        assertTrue(matches(label, ble("monocle", Set.of(), Set.of(REPL_UUID), Map.of())).isEmpty());
        assertTrue(matches(label, ble("Other", Set.of(REPL_UUID), Set.of(), Map.of())).isEmpty());
        assertTrue(matches(label, cached(RadioScanner.Mode.BLE, "monocle", Set.of(REPL_UUID))).isEmpty());
    }

    @Test public void activeLookRequiresTheEntireDefaultManufacturerDataInBle() {
        String label = "ActiveLook 안경 계열";
        assertEquals(1, matches(label, ble(null, Set.of(), Set.of(), Map.of(0xDAFA, "08F2"))).size());
        assertTrue(matches(label, ble(null, Set.of(), Set.of(), Map.of(0xDAFA, "0803"))).isEmpty());
        assertTrue(matches(label, ble(null, Set.of(), Set.of(), Map.of(0xDAFA, "08F200"))).isEmpty());
        assertTrue(matches(label, new RadioScanner.Signal(RadioScanner.Mode.CLASSIC, "test", null, -65,
                Set.of(0xDAFA), Set.of(), Set.of(), Map.of(0xDAFA, "08F2"), Map.of())).isEmpty());
    }

    @Test public void audioFramesRequireTheExactNameFromAnActiveBluetoothObservation() {
        String jbl = "JBL Soundgear Frames";
        String jlab = "JLab JBuds Frames";
        assertEquals(1, matches(jbl, ble("jbl soundgear frames", Set.of(), Set.of(), Map.of())).size());
        assertEquals(1, matches(jlab, new RadioScanner.Signal(RadioScanner.Mode.CLASSIC, "test", jlab, -65,
                Set.of(), Set.of(), Set.of())).size());
        assertTrue(matches(jbl, ble(jbl + " Demo", Set.of(), Set.of(), Map.of())).isEmpty());
        assertTrue(matches(jbl, cached(RadioScanner.Mode.BLE, jbl, Set.of())).isEmpty());
        assertTrue(matches(jlab, cached(RadioScanner.Mode.CLASSIC, jlab, Set.of())).isEmpty());
    }

    private static List<DeviceMatcher.Match> matches(String label, RadioScanner.Signal signal) {
        return DeviceMatcher.match(signal, CONFIG).stream()
                .filter(match -> match.ruleId().equals("builtin/" + label)).toList();
    }

    private static RadioScanner.Signal ble(String name, Set<String> uuids, Set<String> serviceDataKeys,
                                          Map<Integer, String> manufacturerData) {
        return new RadioScanner.Signal(RadioScanner.Mode.BLE, "test", name, -65, manufacturerData.keySet(),
                uuids, serviceDataKeys, manufacturerData, Map.of());
    }

    private static RadioScanner.Signal cached(RadioScanner.Mode source, String name, Set<String> uuids) {
        return new RadioScanner.Signal(source, "test", name, -65, Set.of(), uuids, Set.of(), Map.of(), Map.of(),
                null, name, RadioScanner.NameOrigin.CACHE, 0, null, null, null, false);
    }
}
