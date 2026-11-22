package com.uplusys.deviceguard;

import org.junit.Test;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;
import static org.junit.Assert.*;

public class DeviceMatcherTest {
    @Test public void deletedTargetsDisableNameUuidAndCompanyRules() {
        assertTrue(DeviceMatcher.classify("Even G2_01_L_123456", Set.of(),
                Set.of(DeviceCatalog.G2_UUID), Set.of(DeviceCatalog.G2_UUID), Set.of("Even Realities G2")).isEmpty());
        assertTrue(DeviceMatcher.classify("Rokid Glasses", Set.of(), Set.of(), Set.of(), Set.of("Rokid Glasses")).isEmpty());
        assertTrue(DeviceMatcher.classify("Vuzix Z100", Set.of(0x060C), Set.of(), Set.of(), Set.of("Vuzix")).isEmpty());
        assertTrue(DeviceMatcher.classify("Glasses_4097", Set.of(), Set.of(), Set.of(),
                Set.of("Glasses 이름 스마트글래스")).isEmpty());
        assertTrue(DeviceMatcher.classify("Ray-Ban Meta", Set.of(0x01AB), Set.of(), Set.of(),
                Set.of("Ray-Ban / Meta")).isEmpty());
        assertFalse(DeviceMatcher.classify("Glasses_4097", Set.of(), Set.of(DeviceCatalog.HEYCYAN_UUID), Set.of(),
                Set.of("Glasses 이름 스마트글래스")).isEmpty());
        Set<String> all = DeviceCatalog.ENTRIES.stream().map(DeviceCatalog.Entry::label)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(DeviceMatcher.classify("Glasses_4097", Set.of(0x060C, 0x01AB, 0x03C2),
                Set.of(DeviceCatalog.FRAME_UUID, DeviceCatalog.G2_UUID, DeviceCatalog.HEYCYAN_UUID,
                        DeviceCatalog.ROKID_DISCOVERY_UUID), Set.of(), all).isEmpty());
    }

    @Test public void scanServiceUsesDeletedTargetsAndSkipsUnassignedRules() {
        RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "01:23:45:67:89:AB",
                "Even G2_01_L_123456", -60, Set.of(), Set.of(DeviceCatalog.G2_UUID), Set.of());
        SavedTargets.Snapshot defaults = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of());
        assertFalse(ScanService.match(signal, defaults).isEmpty());
        SavedTargets.Snapshot deleted = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(), Set.of("Even Realities G2"));
        assertTrue(ScanService.match(signal, deleted).isEmpty());
        SavedTargets.Snapshot custom = new SavedTargets.Snapshot(Set.of(signal.name()), Set.of(), Set.of(),
                deleted.deletedBuiltins());
        assertEquals(DeviceCategory.CUSTOM, custom.rules().get(0).category());
        assertTrue(ScanService.match(signal, custom).isEmpty());
    }

    @Test public void rokidTwoSignalsStrengthenCandidate() {
        String discovery = "00009100-0000-1000-8000-00805f9b34fb";
        String serviceData = "00009a01-0000-1000-8000-00805f9b34fb";
        List<DeviceMatcher.Match> preliminary = DeviceMatcher.classify(null, Collections.emptySet(),
                Collections.singleton(discovery), Collections.emptySet());
        List<DeviceMatcher.Match> corroborated = DeviceMatcher.classify(null, Collections.emptySet(),
                Collections.singleton(discovery), Collections.singleton(serviceData));
        assertEquals(1, preliminary.size());
        assertEquals(1, preliminary.get(0).strength());
        assertEquals(2, corroborated.get(0).strength());
    }

    @Test public void manufacturerAloneIsWeakEvidence() {
        List<DeviceMatcher.Match> matches = DeviceMatcher.classify(null, Collections.singleton(0x01AB),
                Collections.emptySet(), Collections.emptySet());
        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).strength());
    }

    @Test public void unknownDeviceDoesNotMatch() {
        assertTrue(DeviceMatcher.classify("Ordinary Headphones", Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet()).isEmpty());
        assertTrue(DeviceMatcher.classifyWifi("Ordinary Access Point").isEmpty());
    }

    @Test public void expandedCatalogRecognizesDocumentedAndResearchNames() {
        Map<String, String> names = Map.ofEntries(
                Map.entry("Solos AirGo™ 3 1234", "Solos AirGo 3"),
                Map.entry("Solos AirGoA5 1234", "Solos AirGo A5"),
                Map.entry("Solos Vision ABCD", "Solos AirGo V"),
                Map.entry("Rokid Glass3_0140", "Rokid Glass3"),
                Map.entry("G1_45_L_92333", "Even Realities G1"),
                Map.entry("Even G2_01_R_123456", "Even Realities G2"),
                Map.entry("VIVE Eagle", "VIVE Eagle"),
                Map.entry("Lucyd Armor", "Lucyd"),
                Map.entry("Mentra Mach1", "Mentra"),
                Map.entry("Glasses_4097", "Glasses 이름"));
        names.forEach((name, expected) -> assertTrue(name, DeviceMatcher.classify(name,
                Set.of(), Set.of(), Set.of()).stream().anyMatch(match -> match.label().contains(expected))));
    }

    @Test public void genericServicesAndNamesDoNotIdentifyGlasses() {
        assertTrue(DeviceMatcher.classify("Sensor", Set.of(), Set.of(DeviceCatalog.G1_UART_UUID), Set.of()).isEmpty());
        assertTrue(DeviceMatcher.classify("Ordinary Device", Set.of(),
                Set.of("0000180f-0000-1000-8000-00805f9b34fb"), Set.of()).isEmpty());
        assertTrue(DeviceMatcher.classifyWifi("Solos Vision ABCD").isEmpty());
    }

    @Test public void glassesSubstringMatchesRegardlessOfPositionCaseOrDeviceSuffix() {
        for (String name : new String[]{"Glasses_4097", "Glasses_1234", "SmartGlasses", "My GLASSES Device", "gLaSsEs"}) {
            assertTrue(name, DeviceMatcher.classify(name, Set.of(), Set.of(), Set.of())
                    .stream().anyMatch(match -> match.label().contains("Glasses 이름")));
        }
        assertTrue(DeviceMatcher.classify(null, Set.of(), Set.of(), Set.of()).isEmpty());
        assertTrue(DeviceMatcher.classify("Glass Speaker", Set.of(), Set.of(), Set.of()).isEmpty());
        assertTrue(DeviceMatcher.classifyWifi("Glasses_1234").isEmpty());
        assertTrue(DeviceMatcher.classify("Xiaomi Smart Audio Glasses", Set.of(), Set.of(), Set.of())
                .stream().anyMatch(match -> match.label().contains("Xiaomi")));
    }

    @Test public void sharedServiceRulesRemainIndependentOfDeviceName() {
        Map<String, String> services = Map.of(
                DeviceCatalog.FRAME_UUID, "Brilliant Labs Frame",
                DeviceCatalog.G2_UUID, "Even Realities G2",
                DeviceCatalog.HEYCYAN_UUID, "HeyCyan",
                DeviceCatalog.ROKID_DISCOVERY_UUID, "Rokid");
        for (String name : new String[]{null, "Glasses_4097", "Glasses_9876", "Renamed Device"}) {
            services.forEach((uuid, label) -> {
                assertTrue(uuid, DeviceMatcher.classify(name, Set.of(), Set.of(uuid), Set.of())
                        .stream().anyMatch(match -> match.label().contains(label)));
                if (!uuid.equals(DeviceCatalog.ROKID_DISCOVERY_UUID)) {
                    assertTrue(uuid, DeviceMatcher.classify(name, Set.of(), Set.of(), Set.of(uuid))
                            .stream().anyMatch(match -> match.label().contains(label)));
                }
            });
        }
    }

    @Test public void g2AndVuzixNeedCorroborationForStrongerMatch() {
        assertEquals(1, DeviceMatcher.classify(null, Set.of(), Set.of(DeviceCatalog.G2_UUID), Set.of()).get(0).strength());
        assertTrue(DeviceMatcher.classify("Even G2_01_L_123456", Set.of(),
                Set.of(DeviceCatalog.G2_UUID), Set.of()).stream().anyMatch(match -> match.strength() == 2));
        assertEquals(1, DeviceMatcher.classify(null, Set.of(0x060C), Set.of(), Set.of()).get(0).strength());
        assertTrue(DeviceMatcher.classify("Vuzix Z100", Set.of(0x060C), Set.of(), Set.of())
                .stream().anyMatch(match -> match.strength() == 2));
    }

    @Test public void catalogRetainsSourcesAndWiredResearchStatus() {
        assertTrue(DeviceCatalog.ENTRIES.size() >= 40);
        for (DeviceCatalog.Entry entry : DeviceCatalog.ENTRIES) {
            assertFalse(entry.source().isEmpty());
            assertFalse(entry.evidence().isEmpty());
        }
        assertTrue(DeviceCatalog.ENTRIES.stream().filter(entry -> entry.label().contains("유선형"))
                .allMatch(entry -> entry.name() == null));
    }

    @Test public void payloadEvidenceIsImmutableAndRetainedInTheSignal() {
        Map<Integer, String> payload = new java.util.HashMap<>();
        payload.put(0x060C, "0123ABCD");
        RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "01:23:45:67:89:AB",
                "Vuzix Z100", -60, Set.of(0x060C), Set.of(), Set.of(DeviceCatalog.G2_UUID),
                payload, Map.of(DeviceCatalog.G2_UUID, "FF10"));
        payload.clear();
        assertEquals("0123ABCD", signal.manufacturerData().get(0x060C));
        assertEquals("FF10", signal.serviceData().get(DeviceCatalog.G2_UUID));
        assertThrows(UnsupportedOperationException.class, () -> signal.manufacturerData().clear());
    }

    @Test public void registeredEvidenceIsNormalized() {
        assertEquals("0x03C2", SavedTargets.normalizeManufacturer(" 03c2 "));
        assertEquals("00009100-0000-1000-8000-00805f9b34fb",
                SavedTargets.normalizeUuid(" 00009100-0000-1000-8000-00805F9B34FB "));
        assertThrows(IllegalArgumentException.class, () -> SavedTargets.normalizeUuid("9100"));
        assertThrows(IllegalArgumentException.class, () -> SavedTargets.normalizeManufacturer("12345"));
    }

    @Test public void candidateAlertsKeepWirelessSourcesSeparate() {
        String address = "01:23:45:67:89:AB";
        ScanObservation ble = observation(RadioScanner.Mode.BLE, address);
        ScanObservation classic = observation(RadioScanner.Mode.CLASSIC, address);
        assertNotEquals(ble.key(), classic.key());
        assertNotEquals(ble.candidateIdentity(), classic.candidateIdentity());
        assertNotEquals(ble.candidateIdentity(), observation(RadioScanner.Mode.WIFI, address).candidateIdentity());
        assertNotEquals(ble.candidateIdentity(), observation(RadioScanner.Mode.BLE, "01:23:45:67:89:AC").candidateIdentity());
    }

    private ScanObservation observation(RadioScanner.Mode mode, String address) {
        return new ScanObservation(new RadioScanner.Signal(mode, address, null, null,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet()), Collections.emptyList());
    }
}
