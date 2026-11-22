package com.uplusys.deviceguard;

import java.util.List;
import java.util.regex.Pattern;

final class DocumentedCameras {
    private static final String DRIVE_PRO_BODY_CONNECTION =
            "https://help.transcendcloud.com/DriveProBody/Android/EN/connection/";
    private static final String GOPRO_UUID = "0000fea6-0000-1000-8000-00805f9b34fb";
    // Android decodes the advertised company ID bytes F2 02 as little-endian 0x02F2.
    private static final int GOPRO_COMPANY_ID = 0x02F2;
    private static final Pattern GOPRO_MANUFACTURER_PAYLOAD = Pattern.compile("0[23][0-9A-Fa-f]{22}");

    private DocumentedCameras() {}

    static List<DeviceCatalog.Entry> entries() {
        return List.of(
                DeviceCatalog.ssid("Transcend DrivePro Body 20", "DrivePro Body 20", "DriveProBody20",
                        "공식 연결 안내의 Wi-Fi SSID DriveProBody20", DRIVE_PRO_BODY_CONNECTION),
                DeviceCatalog.ssid("Transcend DrivePro Body 30", "DrivePro Body 30",
                        "DPB30_[0-9A-Fa-f]{6}", "공식 설명서의 Wi-Fi SSID DPB30_ 뒤 16진수 6자리",
                        "https://www.transcend-info.com/Manual/DriveProBody/DPB30/PDF/DPB30_Manual_EN.pdf"),
                DeviceCatalog.ssid("Transcend DrivePro Body 40", "DrivePro Body 40",
                        "DPB40_[0-9A-Fa-f]{6}", "공식 설명서의 Wi-Fi SSID DPB40_ 뒤 16진수 6자리",
                        "https://us.transcend-info.com/Manual/DriveProBody/DPB40/EN/"),
                DeviceCatalog.ssid("Transcend DrivePro Body 52", "DrivePro Body 52", "DriveProBody52",
                        "공식 연결 안내의 Wi-Fi SSID DriveProBody52", DRIVE_PRO_BODY_CONNECTION),
                DeviceCatalog.ssid("Transcend DrivePro Body 60", "DrivePro Body 60",
                        "DPB60_[0-9A-Fa-f]{6}", "공식 설명서의 Wi-Fi SSID DPB60_ 뒤 16진수 6자리",
                        "https://www.transcend-info.com/Manual/DriveProBody/DPB60/EN/"),
                DeviceCatalog.ssid("Transcend DrivePro Body 70", "DrivePro Body 70",
                        "DPB70_[0-9A-Fa-f]{6}", "공식 설명서의 Wi-Fi SSID DPB70_ 뒤 16진수 6자리",
                        "https://www.transcend-info.com/Manual/DriveProBody/DPB70/PDF/DPB70_Manual_EN.pdf"),
                DeviceCatalog.ssid("RunCam 2", "RunCam 2",
                        "RunCam2-[A-Za-z0-9]{5}", "공식 연결 안내의 Wi-Fi SSID RunCam2- 뒤 영숫자 5자리",
                        "https://support.runcam.com/hc/en-us/articles/115006425807-Android-My-camera-can-not-connect-to-the-APP"),
                DeviceCatalog.ssid("RunCam 3", "RunCam 3",
                        "RunCam3_[A-Za-z0-9]{5}", "공식 설명서의 Wi-Fi SSID RunCam3_ 뒤 영숫자 5자리",
                        "https://www.runcam.com/download/runcam3/RunCam3manual-CN.pdf"),
                DeviceCatalog.ssid("RunCam 3S", "RunCam 3S",
                        "RC3S_[A-Za-z0-9]{6}", "공식 설명서의 Wi-Fi SSID RC3S_ 뒤 영숫자 6자리",
                        "https://www.copterfarm.de/media/pdf/81/60/75/RunCam-3S-Manual-EN.pdf"),
                DeviceCatalog.ssid("SJCAM C110", "C110",
                        "C110_9803c[0-9A-Fa-f]{8}", "공식 설명서에 나온 Wi-Fi SSID C110_9803c 뒤 8자리 예시 범위",
                        "https://static.sjcam.com/www/uploads/2024/12/C110-user-manual.pdf#page=79"),
                DeviceCatalog.ssid("SJCAM SJ6 Pro", "SJ6 Pro",
                        "SJ6PRO_[A-Za-z0-9]{6}", "공식 설명서의 Wi-Fi SSID SJ6PRO_ 뒤 영숫자 6자리",
                        "https://static.sjcam.com/shop/uploads/2022/11/SJ6Pro-user-manual-2022.11.28.pdf#page=9"),
                DeviceCatalog.ssid("SJCAM SJ10 Pro Dual Screen", "SJ10 Pro Dual Screen",
                        "SJ10Pro DualScreen_[A-Za-z0-9]{8}",
                        "공식 설명서의 Wi-Fi SSID SJ10Pro DualScreen_ 뒤 영숫자 8자리",
                        "https://static.sjcam.com/shop/uploads/2022/11/SJ10-Pro-Dual-Screen-user-manual-2022.11.28.pdf#page=11"),
                DeviceCatalog.ssid("AKASO Brave 4 Pro", "Brave 4 Pro",
                        "AKASO-Brave4Pro-[A-Za-z0-9]{2}-[A-Za-z0-9]{4}",
                        "공식 연결 안내의 Wi-Fi SSID AKASO-Brave4Pro-XX-XXXX 형식",
                        "https://support.akasotech.com/hc/en-us/articles/7113678230553-How-to-turn-on-the-WiFi-on-Brave-4-Pro-and-connect-with-the-smartphone"),
                DeviceCatalog.ssid("AKASO Brave 4 Elite", "Brave 4 Elite",
                        "Brave 4 Elite-[A-Za-z0-9]{4}", "공식 설명서의 Wi-Fi SSID Brave 4 Elite- 뒤 영숫자 4자리",
                        "https://www.akasotech.com/imageApi/support/user_manual_pictures/96356_Brave4Elite_EN_V1.0.pdf"),
                DeviceCatalog.ssid("AKASO Brave 8", "Brave 8",
                        "Brave 8_[A-Za-z0-9]{4}", "공식 연결 안내의 Wi-Fi SSID Brave 8_ 뒤 영숫자 4자리",
                        "https://www.akasotech.com/support/Brave-8"),
                DeviceCatalog.ssid("AKASO V50 Elite", "V50 Elite", "AKASO_V50Elite",
                        "공식 설명서의 Wi-Fi SSID AKASO_V50Elite",
                        "https://www.akasotech.com/usermanual/v50elitev2.pdf"),
                DeviceCatalog.published("GoPro Open GoPro 카메라 계열",
                        "HERO9·10·11·12·13 Black, HERO11 Black Mini, LIT HERO, MAX 2",
                        DeviceCategory.CAMERA, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL,
                        "공식 BLE 광고의 서비스 UUID 0xFEA6와 제조사 0x02F2의 스키마 2 또는 3 카메라 데이터",
                        "https://gopro.github.io/OpenGoPro/docs/ble/protocol/ble_setup/#advertisements",
                        DocumentedCameras::goProEvidence));
    }

    private static List<DeviceMatcher.Evidence> goProEvidence(RadioScanner.Signal signal) {
        if (signal.source() != RadioScanner.Mode.BLE || signal.previousResult()
                || !signal.advertisedUuids().contains(GOPRO_UUID)) return List.of();

        String payload = signal.manufacturerData().get(GOPRO_COMPANY_ID);
        if (payload == null || !GOPRO_MANUFACTURER_PAYLOAD.matcher(payload).matches()) return List.of();

        char schema = payload.charAt(1);
        int model = Integer.parseInt(payload.substring(4, 6), 16);
        int capabilities = Integer.parseInt(payload.substring(6, 8), 16);
        // Schema 3 always marks its SSID as fixed and its variant fields as present.
        if (model == 0 || (schema == '3' && (capabilities & 0xC0) != 0xC0)) return List.of();

        return List.of(new DeviceMatcher.Evidence("BLE 광고 서비스 UUID", GOPRO_UUID, "GoPro 서비스 0xFEA6"),
                new DeviceMatcher.Evidence("BLE 제조사 데이터", "0x02F2 · 스키마 " + schema,
                        "GoPro 카메라 광고 구조와 모델 필드 일치"));
    }
}
