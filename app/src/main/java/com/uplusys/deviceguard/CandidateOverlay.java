package com.uplusys.deviceguard;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.material.button.MaterialButton;

/** An optional, dismissible warning. It never wakes or unlocks the device. */
final class CandidateOverlay {
    private final Context context;
    private final WindowManager windows;
    private View window;

    CandidateOverlay(Context context) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
    }

    void show(ScanObservation row, Intent destination) {
        if (!Settings.canDrawOverlays(context)
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !context.getSystemService(PowerManager.class).isInteractive()) return;
        dismiss();
        var themedContext = new ContextThemeWrapper(context, R.style.Theme_DeviceGuard);
        View body = CandidateWarningContent.create(themedContext, row, () -> openDestination(destination));
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF8B1726);
        background.setStroke(Math.max(1, padding / 10), 0xFFFF8A80);
        background.setCornerRadius(padding);
        body.setBackground(background);
        body.setElevation(padding);
        TextView title = body.findViewById(R.id.candidate_warning_heading);
        title.setTextColor(0xFFFFFFFF);
        View summary = body.findViewById(R.id.candidate_warning_summary);
        var summaryBackground = (GradientDrawable) summary.getBackground().mutate();
        summaryBackground.setColor(0xFF681522);
        int border = Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));
        summaryBackground.setStroke(border, 0xFFBB5965);
        body.findViewById(R.id.candidate_warning_divider).setBackgroundColor(0xFFBB5965);
        TextView nameLabel = body.findViewById(R.id.candidate_warning_name_label);
        TextView matchLabel = body.findViewById(R.id.candidate_warning_match_label);
        nameLabel.setTextColor(0xFFFFC6CB);
        matchLabel.setTextColor(0xFFFFC6CB);
        TextView name = body.findViewById(R.id.candidate_warning_name);
        TextView match = body.findViewById(R.id.candidate_warning_match);
        name.setTextColor(0xFFFFFFFF);
        match.setTextColor(0xFFFFFFFF);
        MaterialButton details = body.findViewById(R.id.candidate_warning_details);
        details.setBackgroundTintList(ColorStateList.valueOf(0xFFFFEBEE));
        details.setTextColor(0xFF7F1020);
        details.setIconTint(ColorStateList.valueOf(0xFF7F1020));
        body.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            dismiss();
            return true;
        });
        ViewCompat.replaceAccessibilityAction(body, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS,
                null, (view, arguments) -> {
                    dismiss();
                    return true;
                });
        int width = Math.min(context.getResources().getDisplayMetrics().widthPixels - padding * 2, padding * 22);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        try {
            windows.addView(body, params);
            window = body;
        } catch (SecurityException | WindowManager.BadTokenException unavailable) {
            android.util.Log.w("CandidateOverlay", "Overlay permission unavailable", unavailable);
        }
    }

    private void openDestination(Intent destination) {
        try {
            context.startActivity(new Intent(destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (SecurityException | android.content.ActivityNotFoundException unavailable) {
            android.util.Log.w("CandidateOverlay", "Cannot open detection details", unavailable);
        } finally {
            dismiss();
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
