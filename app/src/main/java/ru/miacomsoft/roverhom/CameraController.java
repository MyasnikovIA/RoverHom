package ru.miacomsoft.roverhom;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.miacomsoft.roverhom.processing.FrameProcessor;

public class CameraController {
    private static final String TAG = "CameraController";

    private Camera camera;
    private SurfaceTexture dummyTexture;
    private int cameraId;

    private volatile int previewW, previewH;
    private volatile int rotation;
    private volatile int targetFps;
    private volatile boolean grayscale;

    private final FrameBroadcaster broadcaster;
    private FrameProcessor processor;
    private HandlerThread worker;
    private Handler handler;
    private boolean running = false;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private long lastFrameTime = 0;

    public void setProcessor(FrameProcessor p) { this.processor = p; }

    public synchronized boolean start(int reqW, int reqH, int rot, int fps, boolean gray) {
        stop();
        this.rotation = rot;
        this.targetFps = Math.max(1, Math.min(30, fps));
        this.grayscale = gray;

        try { Thread.sleep(300); } catch (Exception ignored) {}

        Camera cam = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Log.i(TAG, "Trying to open Camera " + cameraId + ", attempt " + attempt);
                cam = Camera.open(cameraId);
                if (cam != null) break;
            } catch (Exception e) {
                Log.w(TAG, "Camera.open(" + cameraId + ") failed: " + e.getMessage());
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
        }

        if (cam == null) return false;
        this.camera = cam;

        try {
            if (!applyParams(reqW, reqH)) return false;

            dummyTexture = new SurfaceTexture(0);
            dummyTexture.setDefaultBufferSize(previewW, previewH);
            camera.setPreviewTexture(dummyTexture);

            worker = new HandlerThread("CamWorker_" + cameraId);
            worker.start();
            handler = new Handler(worker.getLooper());

            setupCallback();
            addBuffers();

            camera.startPreview();
            running = true;
            Log.i(TAG, "Camera " + cameraId + " started OK");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Start error for Camera " + cameraId + ": " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void updateSettings(int reqW, int reqH, int rot, int fps, boolean gray) {
        this.rotation = rot;
        this.targetFps = Math.max(1, Math.min(30, fps));
        this.grayscale = gray;

        if (running && camera != null) {
            if (reqW != previewW || reqH != previewH) {
                try {
                    Log.i(TAG, "Live updating resolution to " + reqW + "x" + reqH);
                    running = false;
                    camera.stopPreview();
                    camera.setPreviewCallbackWithBuffer(null);

                    if (applyParams(reqW, reqH)) {
                        dummyTexture.setDefaultBufferSize(previewW, previewH);
                        camera.setPreviewTexture(dummyTexture);
                        setupCallback();
                        addBuffers();
                        camera.startPreview();
                    }
                    running = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update settings live", e);
                    running = true;
                }
            }
        }
    }

    private void setupCallback() {
        camera.setPreviewCallbackWithBuffer((data, camRef) -> {
            if (!running) {
                if (camRef != null) camRef.addCallbackBuffer(data);
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastFrameTime < (1000 / targetFps) || isProcessing.get()) {
                if (camRef != null) camRef.addCallbackBuffer(data);
                return;
            }
            lastFrameTime = now;

            // Копируем буфер, чтобы избежать разрывов кадра
            byte[] frameCopy = new byte[data.length];
            System.arraycopy(data, 0, frameCopy, 0, data.length);

            if (camRef != null) camRef.addCallbackBuffer(data);

            isProcessing.set(true);
            handler.post(() -> {
                try {
                    byte[] processed = frameCopy;
                    if (processor != null) processed = processor.process(processed, previewW, previewH);
                    byte[] jpeg = encodeToJpeg(processed);
                    if (jpeg != null) broadcaster.broadcast(cameraId, jpeg);
                } catch (Exception e) {
                    Log.e(TAG, "Process error", e);
                } finally {
                    isProcessing.set(false);
                }
            });
        });
    }

    private void addBuffers() {
        int bufSize = previewW * previewH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
        camera.addCallbackBuffer(new byte[bufSize]);
        camera.addCallbackBuffer(new byte[bufSize]);
    }

    private boolean applyParams(int reqW, int reqH) {
        try {
            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> supportedSizes = p.getSupportedPreviewSizes();
            int bestW = reqW, bestH = reqH;
            boolean found = false;
            for (Camera.Size s : supportedSizes) {
                if (s.width == reqW && s.height == reqH) { found = true; break; }
            }
            if (!found) {
                bestW = supportedSizes.get(0).width;
                bestH = supportedSizes.get(0).height;
                for (Camera.Size s : supportedSizes) {
                    if (s.width == 320 && s.height == 240) { bestW = 320; bestH = 240; break; }
                }
            }
            p.setPreviewSize(bestW, bestH);
            p.setPreviewFormat(ImageFormat.NV21);

            try {
                List<int[]> fpsRanges = p.getSupportedPreviewFpsRange();
                if (fpsRanges != null && !fpsRanges.isEmpty()) {
                    int targetMs = targetFps * 1000;
                    int[] bestRange = fpsRanges.get(0);
                    for (int[] f : fpsRanges) {
                        if (f[1] <= targetMs + 5000 && f[1] > bestRange[1]) bestRange = f;
                    }
                    p.setPreviewFpsRange(bestRange[0], bestRange[1]);
                }
            } catch (Exception ignored) {}

            camera.setParameters(p);
            previewW = camera.getParameters().getPreviewSize().width;
            previewH = camera.getParameters().getPreviewSize().height;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "applyParams error", e);
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        if (dummyTexture != null) {
            try { dummyTexture.release(); } catch (Exception ignored) {}
            dummyTexture = null;
        }
        if (worker != null) {
            worker.quit();
            try { worker.join(500); } catch (InterruptedException ignored) {}
            worker = null;
            handler = null;
        }
        isProcessing.set(false);
    }

    private byte[] encodeToJpeg(byte[] nv21) {
        try {
            if (grayscale) {
                int ySize = previewW * previewH;
                for (int i = ySize; i < nv21.length; i++) {
                    nv21[i] = (byte) 128;
                }
            }
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, previewW, previewH, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Качество 50% снижает нагрузку на память
            yuv.compressToJpeg(new Rect(0, 0, previewW, previewH), 50, out);
            byte[] jpeg = out.toByteArray();

            if (rotation != 0) {
                android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotation);
                android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (rotated != src) src.recycle();
                ByteArrayOutputStream out2 = new ByteArrayOutputStream();
                rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, out2);
                rotated.recycle();
                return out2.toByteArray();
            }
            return jpeg;
        } catch (Exception e) {
            return null;
        }
    }
    public CameraController(int cameraId, FrameBroadcaster broadcaster) {
        this.cameraId = cameraId;
        this.broadcaster = broadcaster;
    }

    // ДОБАВИТЬ ВОТ ЭТОТ МЕТОД:
    public int getCameraId() {
        return cameraId;
    }
}