package com.uplusys.deviceguard;

import org.junit.Test;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class DetectionTextTest {
    @Test public void warningIncludesEveryMatchingValueAndRule() {
        RadioScanner.Signal signal = signal("Glasses_4097", Set.of(0x03C2, 0x9999),
                Set.of(DeviceCatalog.HEYCYAN_UUID), Set.of());
        ScanObservation row = new ScanObservation(signal,
                ScanService.match(signal, new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of())));
        assertEquals(Set.of("이름 일치", "제조사 정보 일치", "광고 서비스 UUID 일치"),
                DetectionText.evidence(row).stream().map(DeviceMatcher.Evidence::type).collect(Collectors.toSet()));
        String warning = DetectionText.warning(row);
        assertTrue(warning.contains("기기명  Glasses_4097"));
        assertTrue(warning.contains("기기 이름에 ‘Glasses’ 포함"));
        assertTrue(warning.contains(DeviceCatalog.HEYCYAN_UUID));
        assertTrue(warning.contains("0x03C2"));
        assertFalse(warning.contains("0x9999"));
        assertFalse(warning.contains("후보"));
        assertTrue(warning.contains("수신값"));
        assertTrue(warning.contains("일치 기준"));
    }

    @Test public void identicalEvidenceIsDeduplicatedWhileDifferentObservedFieldsRemainSeparate() {
        RadioScanner.Signal signal = signal("Even G2_01_L_123456", Set.of(),
                Set.of(DeviceCatalog.G2_UUID), Set.of(DeviceCatalog.G2_UUID));
        List<DeviceMatcher.Match> matches = DeviceMatcher.classify(signal.name(), signal.companies(),
                signal.advertisedUuids(), signal.serviceDataKeys());
        assertTrue(matches.size() > 1);
        List<DeviceMatcher.Evidence> evidence = DetectionText.evidence(new ScanObservation(signal, matches));
        assertEquals(Set.of("이름 일치", "광고 서비스 UUID 일치", "서비스 데이터 키 일치"),
                evidence.stream().map(DeviceMatcher.Evidence::type).collect(Collectors.toSet()));
        assertEquals(1, evidence.stream().filter(item -> item.type().equals("이름 일치")).count());
        assertEquals(2, evidence.stream().filter(item -> item.received().equals(DeviceCatalog.G2_UUID)).count());
        List<DeviceMatcher.Match> duplicated = new java.util.ArrayList<>(matches);
        duplicated.addAll(matches);
        assertEquals(evidence, DetectionText.evidence(new ScanObservation(signal, duplicated)));
        assertThrows(UnsupportedOperationException.class, () -> matches.get(0).evidence().clear());
    }

    @Test public void customEvidenceContainsOnlyActuallyMatchedValues() {
        RadioScanner.Signal signal = signal("My Device", Set.of(0x03C2, 0x9999),
                Set.of(DeviceCatalog.G2_UUID), Set.of(DeviceCatalog.G2_UUID));
        Set<String> deleted = DeviceCatalog.ENTRIES.stream().map(DeviceCatalog.Entry::label).collect(Collectors.toSet());
        SavedTargets.Snapshot legacy = new SavedTargets.Snapshot(Set.of("my device"),
                Set.of(DeviceCatalog.G2_UUID, DeviceCatalog.FRAME_UUID), Set.of("0x03C2"), deleted);
        var assigned = legacy.rules().stream().map(rule -> new DetectionRule(rule.id(), DeviceCategory.GLASSES,
                rule.source(), rule.field(), rule.value(), rule.ignoreCase(), rule.alerts(), rule.evidenceLevel(),
                rule.modelScope(), rule.sourceUrl())).collect(Collectors.toList());
        SavedTargets.Snapshot targets = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(), deleted, assigned);
        ScanObservation row = new ScanObservation(signal, ScanService.match(signal, targets));
        assertEquals(Set.of("사용자 등록 · 이름 일치", "사용자 등록 · 제조사 정보 일치",
                        "사용자 등록 · 광고 서비스 UUID 일치", "사용자 등록 · 서비스 데이터 키 일치"),
                DetectionText.evidence(row).stream().map(DeviceMatcher.Evidence::type).collect(Collectors.toSet()));
        assertEquals(Set.of("My Device", DeviceCatalog.G2_UUID, "0x03C2"),
                DetectionText.evidence(row).stream().map(DeviceMatcher.Evidence::received).collect(Collectors.toSet()));
        assertEquals(2, DetectionText.evidence(row).stream()
                .filter(item -> item.received().equals(DeviceCatalog.G2_UUID)).count());
        String text = DetectionText.fullEvidence(row);
        assertTrue(text.contains("My Device"));
        assertTrue(text.contains(DeviceCatalog.G2_UUID));
        assertTrue(text.contains("0x03C2"));
        assertFalse(text.contains(DeviceCatalog.FRAME_UUID));
        assertFalse(text.contains("0x9999"));
    }

    @Test public void receivedDeviceNamesArePreservedAndLegacyReasonsRemainReadable() {
        RadioScanner.Signal signal = signal("후보기기", Set.of(), Set.of(), Set.of());
        ScanObservation row = new ScanObservation(signal, List.of(new DeviceMatcher.Match("이전 후보", "이전 탐지 설명", 1)));
        assertTrue(DetectionText.warning(row).contains("기기명  후보기기"));
        assertTrue(DetectionText.fullEvidence(row).contains("이전 탐지 설명"));
        assertEquals("이전", DetectionText.targets(row));
        assertEquals("의심 기기 2건", DetectionText.display("후보 2건"));
    }

    private RadioScanner.Signal signal(String name, Set<Integer> companies, Set<String> uuids, Set<String> keys) {
        return new RadioScanner.Signal(RadioScanner.Mode.BLE, "01:23:45:67:89:AB", name, -58, companies, uuids, keys);
    }
}
