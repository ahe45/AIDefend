package com.uplusys.deviceguard;

import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.util.Locale;

final class DeviceFinder {
    private final ScannerActivity activity;
    private final ScanObservation target;
    private final String session;
    private final TextView strength;
    private final TextView band;
    private final TextView trend;
    private final TextView received;
    private final SignalStrengthView meter;
    final AlertDialog dialog;

    DeviceFinder(ScannerActivity activity, ScanObservation target, String session, Runnable dismissed) {
        this.activity = activity;
        this.target = target;
        this.session = session;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int space = activity.getResources().getDimensionPixelSize(R.dimen.space_medium);
        body.setPadding(space, 0, space, space);
        strength = ScanScreen.label(activity, "");
        strength.setTextSize(40);
        strength.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(strength);
        band = ScanScreen.label(activity, "");
        band.setTextSize(17);
        body.addView(band);
        meter = new SignalStrengthView(activity);
        body.addView(meter, new LinearLayout.LayoutParams(-1, Math.round(
                250 * activity.getResources().getDisplayMetrics().density)));
        trend = ScanScreen.label(activity, "");
        trend.setTextSize(19);
        trend.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(trend);
        received = ScanScreen.label(activity, "");
        body.addView(received);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(body);
        dialog = new IconDialogBuilder(activity).setTitle("위치 찾기 · " + target.name())
                .setView(scroll).setPositiveButton("닫기", null).create();
        dialog.setOnDismissListener(view -> dismissed.run());
        dialog.show();
        render();
    }

    void render() {
        SignalTracker.Reading value = activity.signalReading(target.key(), session);
        boolean running = activity.scanRunning();
        long age = value == null ? 0 : Math.max(0, SystemClock.elapsedRealtime() - value.lastSeen());
        boolean fresh = value != null && running && age <= SignalTracker.STALE_MS;
        meter.update(value, fresh);
        strength.setText(value == null ? "측정값 없음"
                : String.format(Locale.KOREA, fresh ? "%.0f dBm" : "마지막 %.0f dBm", value.smoothed()));
        String category = value == null ? "" : value.smoothed() < -90 ? "매우 약함"
                : value.smoothed() < -80 ? "약함" : value.smoothed() < -70 ? "보통"
                : value.smoothed() < -60 ? "강함" : "매우 강함";
        band.setText(fresh ? category + " · " + SignalStrengthView.level(value.smoothed()) + "/20단계 · 평균 신호"
                : "현재 신호 표시 중지");
        trend.setTextColor(activity.getColor(R.color.on_surface));
        if (!running) trend.setText("스캔이 중지되어 있습니다. 스캔 화면에서 스캔을 시작하세요.");
        else if (value == null) trend.setText("선택한 기기의 새 신호를 기다리는 중입니다.");
        else if (!fresh) trend.setText("신호 수신 끊김 · 마지막 값은 현재 위치를 나타내지 않습니다.");
        else if (value.samples() < 4) trend.setText("신호 측정 중 · 잠시 기다려 주세요.");
        else if (Math.abs(value.change()) >= 3) {
            trend.setText(String.format(Locale.KOREA, "%s · %+.1f dB",
                    value.change() >= 3 ? "↑ 강해지는 중" : "↓ 약해지는 중", value.change()));
            trend.setTextColor(value.change() >= 3 ? 0xFF279B74 : 0xFFC17D13);
        } else trend.setText("→ 신호 강도 변화가 작습니다.");
        received.setText(value == null ? "신호를 수신해야 위치 찾기가 가능합니다."
                : "마지막 수신 · " + age / 1000 + "초 전"
                + (fresh ? " · 순간값 " + value.latest() + " dBm" : ""));
    }
}
