package com.glassesscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import java.util.ArrayDeque;

/** A fixed-scale RSSI meter and a thirty-second trace, not a distance estimate. */
final class SignalStrengthView extends View {
    private record Point(long time, double rssi) {}
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final int foreground;
    private final int muted;
    private boolean fresh;
    private double rssi;
    private long plotted = -1;

    SignalStrengthView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        foreground = context.getColor(R.color.on_surface);
        muted = context.getColor(R.color.muted);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    static int level(double rssi) {
        return Math.max(0, Math.min(20, (int) Math.round((rssi + 100) / 70 * 20)));
    }

    void update(SignalTracker.Reading reading, boolean current) {
        fresh = current;
        if (reading != null) {
            rssi = reading.smoothed();
            if (current && reading.lastSeen() > plotted) {
                plotted = reading.lastSeen();
                points.addLast(new Point(plotted, rssi));
            }
        }
        long now = SystemClock.elapsedRealtime();
        while (!points.isEmpty() && (points.size() > 120 || now - points.peekFirst().time() > 30_000)) points.removeFirst();
        setContentDescription(current
                ? "신호 세기 " + level(rssi) + "/20단계. 최근 30초 평균 신호 변화 그래프."
                : "현재 신호 없음. 그래프는 최근 수신 기록입니다.");
        invalidate();
    }

    private float dp(float value) { return value * density; }

    private void text(Canvas canvas, String value, float x, float y, int color, float size) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        canvas.drawText(value, x, y, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float gap = dp(3);
        float segment = (width - gap * 19) / 20;
        int active = fresh ? level(rssi) : 0;
        int tint = rssi < -80 ? 0xFFE09A25 : rssi < -65 ? 0xFF438BE0 : 0xFF279B74;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 20; i++) {
            paint.setColor(i < active ? tint : muted);
            paint.setAlpha(i < active ? 255 : 35);
            canvas.drawRoundRect(i * (segment + gap), dp(3),
                    i * (segment + gap) + segment, dp(31), dp(2), dp(2), paint);
        }
        paint.setAlpha(255);
        text(canvas, "-100", 0, dp(49), muted, 11);
        text(canvas, "약함", width * .28f, dp(49), muted, 11);
        text(canvas, "강함", width * .63f, dp(49), muted, 11);
        paint.setTextSize(dp(11));
        String end = "-30 dBm";
        text(canvas, end, width - paint.measureText(end), dp(49), muted, 11);
        text(canvas, "최근 30초 · 평균 RSSI", 0, dp(77), foreground, 13);
        float left = dp(35), right = width - dp(8), top = dp(94), bottom = getHeight() - dp(24);
        if (right <= left || bottom <= top) return;
        for (int dbm = -100; dbm <= -40; dbm += 20) {
            float y = bottom - (dbm + 100) / 70f * (bottom - top);
            paint.setColor(muted);
            paint.setAlpha(45);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(left, y, right, y, paint);
            paint.setAlpha(255);
            text(canvas, Integer.toString(dbm), 0, y + dp(4), muted, 11);
        }
        text(canvas, "30초 전", left, getHeight() - dp(4), muted, 11);
        paint.setTextSize(dp(11));
        text(canvas, "지금", right - paint.measureText("지금"), getHeight() - dp(4), muted, 11);
        long now = SystemClock.elapsedRealtime();
        path.reset();
        long previous = -1;
        float lastX = 0, lastY = 0;
        for (Point point : points) {
            float x = left + Math.max(0, 1 - (now - point.time()) / 30_000f) * (right - left);
            float y = bottom - (float) ((Math.max(-100, Math.min(-30, point.rssi())) + 100) / 70) * (bottom - top);
            if (previous < 0 || point.time() - previous > SignalTracker.STALE_MS) path.moveTo(x, y);
            else path.lineTo(x, y);
            previous = point.time();
            lastX = x;
            lastY = y;
        }
        paint.setColor(fresh ? 0xFF438BE0 : muted);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (!points.isEmpty()) canvas.drawCircle(lastX, lastY, dp(4), paint);
        else text(canvas, "신호 수신 대기", left + dp(12), top + dp(28), muted, 13);
    }
}
