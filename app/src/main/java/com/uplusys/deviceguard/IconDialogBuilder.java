package com.uplusys.deviceguard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Keep every dialog action legible as an icon plus text. */
final class IconDialogBuilder extends MaterialAlertDialogBuilder {
    private record OpenDialog(java.lang.ref.WeakReference<AlertDialog> reference, boolean setup) {}
    private static final java.util.Map<Context, java.util.List<OpenDialog>> OPEN = new java.util.WeakHashMap<>();
    private final Context owner;
    private boolean setup;

    IconDialogBuilder(Context context) { super(context); owner = context; }

    IconDialogBuilder duringSetup() { setup = true; return this; }

    static void dismissAppDialogs(Context owner) {
        java.util.List<OpenDialog> dialogs = OPEN.get(owner);
        if (dialogs == null) return;
        for (OpenDialog entry : new java.util.ArrayList<>(dialogs)) {
            AlertDialog dialog = entry.reference().get();
            if (!entry.setup() && dialog != null && dialog.isShowing()) dialog.dismiss();
        }
        dialogs.removeIf(entry -> entry.reference().get() == null || !entry.reference().get().isShowing());
    }

    @Override public AlertDialog create() {
        AlertDialog dialog = super.create();
        java.util.List<OpenDialog> dialogs = OPEN.computeIfAbsent(owner, ignored -> new java.util.ArrayList<>());
        dialogs.removeIf(entry -> entry.reference().get() == null || !entry.reference().get().isShowing());
        dialogs.add(new OpenDialog(new java.lang.ref.WeakReference<>(dialog), setup));
        dialog.setOnShowListener(ignored -> {
            icon(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            icon(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            icon(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
        });
        return dialog;
    }

    static void icon(Button button) {
        if (button == null) return;
        String label = button.getText().toString();
        int resource = label.equals("위치 찾기") ? R.drawable.ic_permission_location
                : label.contains("닫기") || label.contains("취소") ? R.drawable.ic_close : R.drawable.ic_action;
        icon(button, resource);
    }

    private static void icon(Button button, int resource) {
        if (button == null) return;
        if (button instanceof com.google.android.material.button.MaterialButton material) {
            material.setIconResource(resource);
            material.setIconTint(android.content.res.ColorStateList.valueOf(button.getCurrentTextColor()));
        } else {
            Drawable drawable = AppCompatResources.getDrawable(button.getContext(), resource);
            if (drawable != null) {
                drawable = drawable.mutate();
                drawable.setTint(button.getCurrentTextColor());
            }
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null);
            button.setCompoundDrawablePadding(Math.round(8 * button.getResources().getDisplayMetrics().density));
        }
    }
}
