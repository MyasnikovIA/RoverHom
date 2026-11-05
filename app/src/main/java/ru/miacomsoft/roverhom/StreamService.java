package ru.miacomsoft.roverhom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class StreamService extends Service {
    private static final String TAG = "StreamService";
    private static final String CH_ID = "rovercam";
    private static final int NOTIF_ID = 1;

    public static final String PREFS = "rover_prefs";
    public static final String KEY_PORT = "current_port";

    private AppConfig config;
    private FrameBroadcaster broadcaster = new FrameBroadcaster();
    private Map<Integer, CameraController> activeCameras = new HashMap<>();
    private MJPEGServer server;
    private Handler handler = new Handler(Looper.getMainLooper());

    private int lastUsbCount = -1;
    private int lastCameraCount = -1;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.i(TAG, "USB event " + action + ": "
                        + (dev != null ? dev.getDeviceName() : "?"));
                handler.postDelayed(StreamService.this::syncCameras, 1500);
                handler.postDelayed(StreamService.this::syncCameras, 4000);
                handler.postDelayed(StreamService.this::syncCameras, 8000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null) return START_STICKY;

        startForeground(NOTIF_ID, buildNotif("Запуск сервиса..."));
        config = AppConfig.load(this);
        server = new MJPEGServer(this, this);

        try {
            server.start(config.port);
            int actual = server.getPort();
            Log.i(TAG, "Server started on " + actual + " (requested " + config.port + ")");

            if (actual != config.port) {
                config.port = actual;
                config.save(this);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_PORT, actual).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage(), e);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_PORT, -1).apply();
        }

        syncCameras();
        return START_STICKY;
    }

    public synchronized void rescanCameras() { syncCameras(); }

    public synchronized boolean isCameraActive(int id) {
        return activeCameras.containsKey(id);
    }

    private synchronized void syncCameras() {
        int camera1 = 0;
        try { camera1 = Camera.getNumberOfCameras(); } catch (Exception e) {
            Log.e(TAG, "Camera.getNumberOfCameras failed", e);
        }
        int camera2 = 0;
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) camera2 = cm.getCameraIdList().length;
        } catch (Exception e) {
            Log.e(TAG, "CameraManager failed", e);
        }
        int usbAll = countUsbDevices();
        int usbVideo = countUsbVideoDevices();
        int count = Math.max(camera1, camera2);

        Log.i(TAG, "syncCameras: Camera1=" + camera1
                + " Camera2=" + camera2
                + " USB=" + usbAll
                + " USBVideo=" + usbVideo
                + " effective=" + count
                + " configured=" + (config != null ? config.cameras.size() : -1)
                + " active=" + activeCameras.size());

        if (usbAll != lastUsbCount) {
            lastUsbCount = usbAll;
            logUsbDevices();
        }
        if (camera1 != lastCameraCount) {
            lastCameraCount = camera1;
            logCamera2Details();
        }

        if (config == null) return;

        boolean changed = false;

        Iterator<Map.Entry<Integer, CameraController>> it =
                activeCameras.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CameraController> entry = it.next();
            if (entry.getKey() >= count) {
                entry.getValue().stop();
                broadcaster.endStream(entry.getKey());
                it.remove();
                changed = true;
            }
        }

        for (int i = 0; i < count; i++) {
            boolean exists = false;
            for (AppConfig.CameraConfig c : config.cameras) {
                if (c.id == i) { exists = true; break; }
            }
            if (!exists) {
                config.cameras.add(new AppConfig.CameraConfig(i));
                changed = true;
            }
        }

        Iterator<AppConfig.CameraConfig> cfgIt = config.cameras.iterator();
        while (cfgIt.hasNext()) {
            if (cfgIt.next().id >= count) { cfgIt.remove(); changed = true; }
        }

        if (changed) config.save(this);

        // Запускаем только те камеры, которые помечены active.
        // Если железо не позволяет - startCamera вернёт false,
        // а updateCameraConfig откатит active=false.
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.active && !activeCameras.containsKey(c.id)) {
                startCamera(c.id);
            }
        }

        updateNotification(buildNotif("Камер: " + activeCameras.size() + " / " + count
                + " (USB видео: " + usbVideo + ")"));
    }

    private int countUsbDevices() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) return -1;
            HashMap<String, UsbDevice> list = um.getDeviceList();
            return list == null ? -1 : list.size();
        } catch (Exception e) { return -1; }
    }

    private int countUsbVideoDevices() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) return 0;
            HashMap<String, UsbDevice> list = um.getDeviceList();
            if (list == null) return 0;
            int n = 0;
            for (UsbDevice d : list.values()) {
                if (d.getDeviceClass() == 14) { n++; continue; }
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    if (d.getInterface(i).getInterfaceClass() == 14) { n++; break; }
                }
            }
            return n;
        } catch (Exception e) { return 0; }
    }

    private void logUsbDevices() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) return;
            HashMap<String, UsbDevice> list = um.getDeviceList();
            if (list == null) return;
            Log.i(TAG, "=== USB devices: " + list.size() + " ===");
            for (UsbDevice d : list.values()) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ").append(d.getDeviceName())
                        .append(" vid=0x").append(Integer.toHexString(d.getVendorId()))
                        .append(" pid=0x").append(Integer.toHexString(d.getProductId()))
                        .append(" class=").append(d.getDeviceClass())
                        .append(" ifaces=").append(d.getInterfaceCount())
                        .append(" perm=").append(um.hasPermission(d));
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    sb.append("\n     iface[").append(i).append("] class=")
                            .append(d.getInterface(i).getInterfaceClass());
                }
                Log.i(TAG, sb.toString());
            }
        } catch (Exception e) { Log.e(TAG, "logUsbDevices failed", e); }
    }

    private void logCamera2Details() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String[] ids = cm.getCameraIdList();
            Log.i(TAG, "Camera2 IDs: " + (ids == null ? 0 : ids.length));
            if (ids == null) return;
            for (String id : ids) {
                try {
                    android.hardware.camera2.CameraCharacteristics cc =
                            cm.getCameraCharacteristics(id);
                    Integer facing = cc.get(
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    Integer level = cc.get(
                            android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    Log.i(TAG, "  Camera2 id=" + id + " facing=" + facing
                            + " hwLevel=" + level);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public synchronized boolean updateCameraConfig(int id, int w, int h, int rot,
                                                   int fps, boolean gray, boolean active) {
        if (config == null) return false;
        AppConfig.CameraConfig cc = null;
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.id == id) { cc = c; break; }
        }
        if (cc == null) return false;

        boolean resChanged = (cc.width != w || cc.height != h);
        cc.width = w; cc.height = h; cc.rotation = rot; cc.fps = fps;
        cc.grayscale = gray; cc.active = active;

        boolean wasActive = activeCameras.containsKey(id);
        boolean success = true;

        if (active && !wasActive) {
            if (!startCamera(id)) {
                success = false;
                cc.active = false;
                config.save(this);
                Log.w(TAG, "updateCameraConfig: camera " + id
                        + " could not be started (resource limit?)");
            }
        } else if (!active && wasActive) {
            stopCamera(id);
        } else if (active && wasActive) {
            if (resChanged) broadcaster.endStream(id);
            activeCameras.get(id).updateSettings(w, h, rot, fps, gray);
            config.save(this);
        } else {
            config.save(this);
        }
        return success;
    }

    public synchronized boolean startCamera(int id) {
        if (activeCameras.containsKey(id)) return true;
        if (config == null) return false;
        AppConfig.CameraConfig cc = null;
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.id == id) { cc = c; break; }
        }
        if (cc == null) return false;

        try { Thread.sleep(300); } catch (Exception ignored) {}

        CameraController controller = new CameraController(id, broadcaster);
        if (controller.start(cc.width, cc.height, cc.rotation,
                cc.fps, cc.grayscale)) {
            activeCameras.put(id, controller);
            updateNotification(buildNotif("Активно: " + activeCameras.size()));
            return true;
        }
        Log.w(TAG, "startCamera(" + id + ") failed "
                + "(возможно, устройство не поддерживает несколько камер одновременно)");
        return false;
    }

    public synchronized boolean stopCamera(int id) {
        CameraController controller = activeCameras.remove(id);
        if (controller != null) controller.stop();
        broadcaster.endStream(id);
        updateNotification(buildNotif("Активно: " + activeCameras.size()));
        return true;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        for (CameraController cc : activeCameras.values()) cc.stop();
        if (server != null) server.stop();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_PORT, -1).apply();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    public AppConfig getConfig() { return config; }
    public FrameBroadcaster getBroadcaster() { return broadcaster; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "RoverCam",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("RoverCam")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(Notification n) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }
}