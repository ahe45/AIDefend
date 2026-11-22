package com.uplusys.deviceguard;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.List;

final class ScanPolicy {
    record Problem(String message, String action) {}

    private ScanPolicy() {}

    static List<String> missingPermissions(Context context, SessionConfig config) {
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
        for (RadioScanner.Mode route : config.paths()) missing.addAll(missingPermissions(context, route));
        return new ArrayList<>(missing);
    }

    static List<Problem> startupProblems(Context context, SessionConfig config) {
        java.util.LinkedHashSet<Problem> problems = new java.util.LinkedHashSet<>();
        for (RadioScanner.Mode route : config.paths()) problems.addAll(startupProblems(context, route));
        return new ArrayList<>(problems);
    }

    static List<RadioScanner.Mode> executablePaths(Context context, SessionConfig config) {
        List<RadioScanner.Mode> executable = new ArrayList<>();
        for (RadioScanner.Mode route : config.paths()) {
            try {
                if (missingPermissions(context, route).isEmpty() && radioProblem(context, route) == null) executable.add(route);
            } catch (SecurityException changed) {
                // Another selected route can remain available after a permission change.
            }
        }
        return executable;
    }

    static Problem radioProblem(Context context, SessionConfig config) {
        if (!executablePaths(context, config).isEmpty()) return null;
        List<Problem> problems = startupProblems(context, config);
        return problems.isEmpty() ? new Problem("선택한 검색 경로의 권한이 필요합니다.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                : problems.get(0);
    }

    @RequiresApi(29)
    static int serviceType(Context context, SessionConfig config) {
        int type = 0;
        for (RadioScanner.Mode route : executablePaths(context, config)) type |= serviceType(route);
        return type;
    }

    @RequiresApi(29)
    static int serviceType(SessionConfig config) {
        int type = 0;
        for (RadioScanner.Mode route : config.paths()) type |= serviceType(route);
        return type;
    }

    static boolean needsLocation(RadioScanner.Mode mode) {
        // Keep precise location for BLE until the existing advertisements can be
        // compared with neverForLocation on the target hardware.
        return Build.VERSION.SDK_INT < 31 || mode != RadioScanner.Mode.CLASSIC;
    }

    static List<String> missingPermissions(Context context, RadioScanner.Mode mode) {
        List<String> permissions = new ArrayList<>();
        if (needsLocation(mode)
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31 && mode != RadioScanner.Mode.WIFI) {
            for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}) {
                if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) permissions.add(permission);
            }
        }
        return permissions;
    }

    @RequiresApi(29)
    static int serviceType(RadioScanner.Mode mode) {
        if (mode == RadioScanner.Mode.WIFI || Build.VERSION.SDK_INT < 31) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (needsLocation(mode)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        return type;
    }

    static Problem radioProblem(Context context, RadioScanner.Mode mode) {
        if (mode == RadioScanner.Mode.ALL) {
            BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            boolean bluetoothReady = bluetooth != null && bluetooth.getAdapter() != null && bluetooth.getAdapter().isEnabled();
            boolean wifiReady = wifi != null && wifi.isWifiEnabled();
            if (!bluetoothReady && !wifiReady) {
                return new Problem("Bluetooth 또는 Wi-Fi를 켠 뒤 통합 스캔을 시작하세요.", Settings.ACTION_WIRELESS_SETTINGS);
            }
        } else {
            Problem radio = mode == RadioScanner.Mode.WIFI ? wifiProblem(context)
                    : bluetoothProblem(context, mode != RadioScanner.Mode.CLASSIC);
            if (radio != null) return radio;
        }
        return locationProblem(context, mode);
    }

    static List<Problem> startupProblems(Context context, RadioScanner.Mode mode) {
        List<Problem> problems = new ArrayList<>();
        if (mode != RadioScanner.Mode.WIFI) {
            Problem bluetooth = bluetoothProblem(context, mode == RadioScanner.Mode.BLE || mode == RadioScanner.Mode.COMBINED);
            if (bluetooth != null) problems.add(bluetooth);
        }
        if (mode == RadioScanner.Mode.WIFI || mode == RadioScanner.Mode.ALL) {
            Problem wifi = wifiProblem(context);
            if (wifi != null) problems.add(wifi);
        }
        Problem location = locationProblem(context, mode);
        if (location != null) problems.add(location);
        return problems;
    }

    private static Problem bluetoothProblem(Context context, boolean needsBle) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) {
            return new Problem("이 기기에서 Bluetooth를 사용할 수 없습니다.", null);
        }
        if (needsBle && !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return new Problem("이 기기는 BLE 스캔을 지원하지 않습니다. Classic 모드를 선택하세요.", null);
        }
        try {
            if (!manager.getAdapter().isEnabled()) {
                return new Problem("Bluetooth를 켜 주세요.", Settings.ACTION_BLUETOOTH_SETTINGS);
            }
        } catch (SecurityException revoked) {
            return new Problem("Bluetooth 검색 권한을 허용해 주세요.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        }
        return null;
    }

    private static Problem wifiProblem(Context context) {
        WifiManager manager = context.getApplicationContext().getSystemService(WifiManager.class);
        if (manager == null) return new Problem("이 기기에서 Wi-Fi를 사용할 수 없습니다.", null);
        if (!manager.isWifiEnabled()) return new Problem("Wi-Fi를 켜 주세요.", Settings.ACTION_WIFI_SETTINGS);
        return null;
    }

    private static Problem locationProblem(Context context, RadioScanner.Mode mode) {
        if (needsLocation(mode)) {
            LocationManager location = context.getSystemService(LocationManager.class);
            boolean enabled = location != null && (Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled()
                    : location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) return new Problem("위치 서비스를 켜 주세요.", Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        }
        return null;
    }
}
