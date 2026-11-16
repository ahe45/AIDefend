package com.glassesscanner;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An optional, dismissible warning. It never wakes or unlocks the device. */
final class CandidateOverlay {
    private final Context context;
    private final WindowManager windows;
    private View window;

    CandidateOverlay(Context context) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
    }

    void show(String message, Intent destination) {
        if (!Settings.canDrawOverlays(context)
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !context.getSystemService(PowerManager.class).isInteractive()) return;
        dismiss();
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF8B1726);
        background.setStroke(Math.max(1, padding / 10), 0xFFFF8A80);
        background.setCornerRadius(padding);
        body.setBackground(background);
        body.setElevation(padding);
        TextView title = new TextView(context);
        title.setText(R.string.candidate_warning_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        body.addView(title);
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextColor(0xFFFFEBEE);
        text.setTextSize(18);
        text.setPadding(0, padding, 0, padding);
        body.addView(text);
        Button open = new Button(context);
        open.setText(R.string.open_app);
        open.setTextColor(0xFF7F1020);
        open.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFEBEE));
        open.setOnClickListener(view -> {
            // A direct user click on the visible overlay opens our explicit activity.
            try {
                context.startActivity(destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (SecurityException | android.content.ActivityNotFoundException unavailable) {
                android.util.Log.w("CandidateOverlay", "Cannot open candidate details", unavailable);
            } finally {
                dismiss();
            }
        });
        IconDialogBuilder.icon(open);
        body.addView(open);
        Button close = new Button(context);
        close.setText(R.string.confirm);
        close.setTextColor(0xFF7F1020);
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFEBEE));
        close.setOnClickListener(view -> dismiss());
        IconDialogBuilder.icon(close);
        body.addView(close);
        int width = Math.min(context.getResources().getDisplayMetrics().widthPixels - padding * 2, padding * 22);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        try {
            windows.addView(body, params);
            window = body;
        } catch (SecurityException | WindowManager.BadTokenException unavailable) {
            android.util.Log.w("CandidateOverlay", "Overlay permission unavailable", unavailable);
        }
    }

    void dismiss() {
        if (window == null) return;
        try {
            windows.removeView(window);
        } catch (IllegalArgumentException alreadyRemoved) {
            // Permission revocation may have removed the window.
        }
        window = null;
    }
}
