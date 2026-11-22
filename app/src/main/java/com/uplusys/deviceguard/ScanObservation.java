package com.uplusys.deviceguard;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

record ScanObservation(RadioScanner.Signal signal, List<DeviceMatcher.Match> matches) {
    String key() { return key(signal); }
    static String key(RadioScanner.Signal signal) { return signal.source().name() + "/" + signal.address(); }
    String candidateIdentity() {
        return key();
    }
    String name() { return signal.name() == null || signal.name().trim().isEmpty() ? "이름 없음" : signal.name(); }
    boolean candidate() { return !signal.previousResult() && matches.stream().anyMatch(DeviceMatcher.Match::classified); }
    int strength() { return candidate() ? best().strength() : 0; }
    DeviceMatcher.Match best() {
        return matches.stream().max(Comparator.comparingInt(DeviceMatcher.Match::strength)).orElse(null);
    }
    int rssi() { return signal.rssi() == null ? Integer.MIN_VALUE : signal.rssi(); }
    String metadata() {
        return signal.source().label + " · " + (signal.rssi() == null ? "RSSI 없음" : signal.rssi() + " dBm");
    }
    static String company(int id) { return String.format(Locale.ROOT, "0x%04X", id); }
}
