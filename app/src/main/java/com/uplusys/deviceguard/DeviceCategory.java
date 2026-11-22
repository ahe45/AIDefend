package com.uplusys.deviceguard;

import java.util.Set;

enum DeviceCategory {
    GLASSES("스마트글래스", "BLE · 검색 가능한 Bluetooth · 무선 스캔 상태별 차이", Set.of(RadioScanner.Mode.BLE)),
    CAMERA("초소형 카메라·카메라펜", "Wi-Fi AP · 일부 모델 BLE · 내부 저장 전용은 확인 불가", Set.of(RadioScanner.Mode.BLE, RadioScanner.Mode.WIFI)),
    AUDIO("무선 이어피스·이어버드", "BLE · 검색 가능한 Bluetooth · 연결·케이스 상태별 차이", Set.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC)),
    WATCH("스마트워치·스마트밴드", "BLE · 모델과 연결 상태에 따라 스캔 결과 차이", Set.of(RadioScanner.Mode.BLE)),
    CUSTOM("종류 미지정", "기기 종류를 지정하면 스캔에 포함됩니다.", Set.of()),
    UNKNOWN("미분류", "유형을 특정할 근거 부족", Set.of());

    final String label;
    final String support;
    final Set<RadioScanner.Mode> defaultPaths;

    DeviceCategory(String label, String support, Set<RadioScanner.Mode> paths) {
        this.label = label;
        this.support = support;
        defaultPaths = paths;
    }

    boolean selectable() { return this != CUSTOM && this != UNKNOWN; }
    static Set<DeviceCategory> defaults() { return Set.of(GLASSES, CAMERA, AUDIO, WATCH); }
}
