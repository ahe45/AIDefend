package com.uplusys.deviceguard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/** Shared detection evidence for results, warnings, notifications, and saved history. */
final class DetectionText {
    static List<DeviceMatcher.Evidence> evidence(ScanObservation row) {
        LinkedHashSet<DeviceMatcher.Evidence> values = new LinkedHashSet<>();
        for (DeviceMatcher.Match match : row.matches()) {
            if (match.evidence().isEmpty()) {
                values.add(new DeviceMatcher.Evidence("등록 기준 일치",
                        row.name(), display(match.reason())));
            } else values.addAll(match.evidence());
        }
        return new ArrayList<>(values);
    }

    static String targets(ScanObservation row) {
        return row.matches().stream().map(match -> display(match.label()).replace(" 의심 기기", ""))
                .distinct().collect(Collectors.joining(" · "));
    }

    static String summary(ScanObservation row) {
        return evidence(row).stream().map(DeviceMatcher.Evidence::criterion).distinct()
                .collect(Collectors.joining("\n"));
    }

    static String fullEvidence(ScanObservation row) {
        return evidence(row).stream().map(value -> value.type() + "\n수신값  " + value.received()
                + "\n일치 기준  " + value.criterion()).collect(Collectors.joining("\n\n"));
    }

    static String warning(ScanObservation row) {
        return "기기명  " + row.name() + "\n일치 항목  " + targets(row)
                + "\n\n스캔 근거\n" + fullEvidence(row)
                + "\n\n실제 제품 종류와 촬영 여부는 별도 확인 필요.";
    }

    static String display(String text) {
        return text == null ? "" : text.replace("후보", "의심 기기");
    }

    private DetectionText() {}
}
