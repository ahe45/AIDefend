package com.uplusys.deviceguard;

import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.FileProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhotoPreview {
    private static final int MAX_SIZE = 1600;
    private final ScannerActivity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<PickVisualMediaRequest> picker;
    private final ActivityResultLauncher<Uri> camera;
    private File capture;
    private Uri source;
    private boolean loading;
    private boolean destroyed;
    private boolean preserve;
    private AlertDialog dialog;

    PhotoPreview(ScannerActivity activity, Bundle state) {
        this.activity = activity;
        if (state != null) {
            String path = state.getString("photo.capture");
            if (path != null) {
                File file = new File(captureDirectory(), path);
                if (file.isFile() && path.equals(file.getName())) capture = file;
            }
            source = state.getParcelable("photo.source");
        }
        picker = activity.registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) show(uri);
        });
        camera = activity.registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
            if (Boolean.TRUE.equals(saved) && capture != null) show(uri(capture));
            else discardCapture();
        });
        io.execute(() -> {
            File[] stale = captureDirectory().listFiles();
            if (stale == null) return;
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File file : stale) if (file.lastModified() < cutoff && !file.equals(capture)) file.delete();
        });
    }

    void choose() {
        new IconDialogBuilder(activity).setTitle(R.string.photo)
                .setItems(new String[]{"카메라로 촬영", "기기에서 사진 선택"}, (choice, which) -> {
                    if (which == 0) takePhoto();
                    else {
                        try {
                            picker.launch(new PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
                        } catch (ActivityNotFoundException missing) {
                            error("이 기기에서 사진 선택 화면을 열 수 없습니다.");
                        }
                    }
                }).setNegativeButton("취소", null).show();
    }

    private File captureDirectory() { return new File(activity.getCacheDir(), "capture"); }
    private Uri uri(File file) { return FileProvider.getUriForFile(activity, activity.getPackageName() + ".photos", file); }

    private void takePhoto() {
        discardCapture();
        try {
            File directory = captureDirectory();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Camera cache unavailable");
            capture = File.createTempFile("glasses-", ".jpg", directory);
            camera.launch(uri(capture));
        } catch (ActivityNotFoundException missing) {
            discardCapture();
            error("카메라 앱을 열 수 없습니다. 기기에서 사진을 선택할 수 있습니다.");
        } catch (IOException | IllegalArgumentException failure) {
            discardCapture();
            error("촬영 파일을 준비하지 못했습니다.");
        }
    }

    void restore() {
        if (source != null && !loading && dialog == null) show(source);
    }

    void save(Bundle state) {
        if (capture != null) state.putString("photo.capture", capture.getName());
        state.putParcelable("photo.source", source);
    }

    void destroy(boolean changingConfigurations) {
        destroyed = true;
        preserve = changingConfigurations;
        io.shutdownNow();
        if (dialog != null) dialog.dismiss();
        if (!preserve) discardCapture();
    }

    private void show(Uri selected) {
        source = selected;
        loading = true;
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decode(selected);
            } catch (IOException | RuntimeException invalid) {
                android.util.Log.w("DeviceGuard", "Unable to decode selected photo", invalid);
            }
            Bitmap result = bitmap;
            activity.runOnUiThread(() -> {
                if (destroyed || !selected.equals(source)) {
                    if (result != null) result.recycle();
                    return;
                }
                loading = false;
                if (result == null) {
                    source = null;
                    discardCapture();
                    error("사진을 읽을 수 없습니다. 다른 사진을 선택하세요.");
                    return;
                }
                ZoomImage image = new ZoomImage(activity, result);
                int height = activity.getResources().getDisplayMetrics().heightPixels / 2;
                android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
                holder.addView(image, new android.widget.FrameLayout.LayoutParams(-1, height));
                dialog = new IconDialogBuilder(activity).setTitle(R.string.photo)
                        .setView(holder).setNeutralButton("확대/축소", null).setPositiveButton("닫기", null).show();
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> image.toggleZoom());
                dialog.setOnDismissListener(closed -> {
                    dialog = null;
                    image.setImageDrawable(null);
                    if (!preserve) {
                        source = null;
                        discardCapture();
                    }
                });
            });
        });
    }

    private Bitmap decode(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.getContentResolver(), uri),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                        int longer = Math.max(width, height);
                        if (longer > MAX_SIZE) {
                            float ratio = (float) MAX_SIZE / longer;
                            decoder.setTargetSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
                        }
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_SIZE) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            if (stream != null) orientation = new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // Some document providers return images without EXIF metadata.
        }
        Matrix rotation = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rotation.setScale(-1, 1);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> rotation.setScale(1, -1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> rotation.setRotate(180);
            case ExifInterface.ORIENTATION_ROTATE_90 -> rotation.setRotate(90);
            case ExifInterface.ORIENTATION_ROTATE_270 -> rotation.setRotate(270);
            case ExifInterface.ORIENTATION_TRANSPOSE -> { rotation.setRotate(90); rotation.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_TRANSVERSE -> { rotation.setRotate(-90); rotation.postScale(-1, 1); }
            default -> { return bitmap; }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
    }

    private void discardCapture() {
        if (capture == null) return;
        capture.delete();
        capture = null;
    }

    private void error(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }

    private static final class ZoomImage extends AppCompatImageView {
        private final Bitmap bitmap;
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float zoom = 1;
        private float x;
        private float y;
        private float lastX;
        private float lastY;

        ZoomImage(ScannerActivity activity, Bitmap bitmap) {
            super(activity);
            this.bitmap = bitmap;
            setScaleType(ScaleType.MATRIX);
            setImageBitmap(bitmap);
            setContentDescription("선택한 사진");
            scaleDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom = Math.max(1, Math.min(4, zoom * detector.getScaleFactor()));
                    update();
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            update();
        }

        void toggleZoom() {
            zoom = zoom > 1 ? 1 : 2;
            x = y = 0;
            update();
        }

        private void update() {
            if (getWidth() == 0 || getHeight() == 0) return;
            float fit = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight()) * zoom;
            float left = (getWidth() - bitmap.getWidth() * fit) / 2;
            float top = (getHeight() - bitmap.getHeight() * fit) / 2;
            x = clamp(x, Math.max(0, -left));
            y = clamp(y, Math.max(0, -top));
            transform.setScale(fit, fit);
            transform.postTranslate(left + x, top + y);
            setImageMatrix(transform);
        }

        private float clamp(float value, float limit) { return Math.max(-limit, Math.min(limit, value)); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            float px = event.getX(), py = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
                x += px - lastX;
                y += py - lastY;
                update();
            }
            lastX = px;
            lastY = py;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
