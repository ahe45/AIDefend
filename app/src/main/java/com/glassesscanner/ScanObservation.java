package com.glassesscanner;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

record ScanObservation(RadioScanner.Signal signal, List<GlassesMatcher.Match> matches) {
    String key() { return key(signal); }
    static String key(RadioScanner.Signal signal) { return signal.source().name() + "/" + signal.address(); }
    String candidateIdentity() {
        String transport = signal.source() == RadioScanner.Mode.WIFI ? "WIFI" : "BLUETOOTH";
        return transport + "/" + signal.address().toUpperCase(Locale.ROOT);
    }
    String name() { return signal.name() == null || signal.name().trim().isEmpty() ? "이름 없음" : signal.name(); }
    boolean candidate() { return !matches.isEmpty(); }
    int strength() { return candidate() ? best().strength() : 0; }
    GlassesMatcher.Match best() {
        return matches.stream().max(Comparator.comparingInt(GlassesMatcher.Match::strength)).orElse(null);
    }
    int rssi() { return signal.rssi() == null ? Integer.MIN_VALUE : signal.rssi(); }
    String metadata() {
        return signal.source().label + " · " + (signal.rssi() == null ? "RSSI 없음" : signal.rssi() + " dBm");
    }
    String radioEvidence() {
        StringBuilder text = new StringBuilder("주소 " + signal.address());
        signal.companies().stream().sorted().forEach(id -> text.append("\n제조사 ID ").append(company(id))
                .append(" · 데이터 ").append(signal.manufacturerData().getOrDefault(id, "미수집")));
        signal.advertisedUuids().stream().sorted().forEach(uuid -> text.append("\n광고 서비스 UUID ").append(uuid));
        signal.serviceDataKeys().stream().sorted().forEach(uuid -> text.append("\n서비스 데이터 ").append(uuid)
                .append(" · ").append(signal.serviceData().getOrDefault(uuid, "미수집")));
        return text.toString();
    }
    static String company(int id) { return String.format(Locale.ROOT, "0x%04X", id); }
}
