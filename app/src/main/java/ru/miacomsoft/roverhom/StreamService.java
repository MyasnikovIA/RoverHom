package ru.miacomsoft.roverhom;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
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

    private AppConfig config;
    private FrameBroadcaster broadcaster = new FrameBroadcaster();
    private Map<Integer, CameraController> activeCameras = new HashMap<>();
    private MJPEGServer server;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Отслеживание USB-событий
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
                    UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "USB Event: " + action + ". Rescanning in 2s...");
                handler.postDelayed(() -> syncCameras(), 2000);
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
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server", e);
        }

        syncCameras();
        return START_STICKY;
    }

    // Публичный метод для вызова из API
    public synchronized void rescanCameras() {
        syncCameras();
    }

    // Синхронизация списка камер с реальным состоянием системы
    private synchronized void syncCameras() {
        int count;
        try {
            count = Camera.getNumberOfCameras();
        } catch (Exception e) {
            count = 0;
        }

        boolean changed = false;

        // 1. Удаляем камеры, которые отключили (через Iterator, чтобы не было ошибок лямбд)
        Iterator<Map.Entry<Integer, CameraController>> it = activeCameras.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CameraController> entry = it.next();
            if (entry.getKey() >= count) {
                entry.getValue().stop();
                broadcaster.endStream(entry.getKey());
                it.remove();
                changed = true;
            }
        }

        // 2. Добавляем новые камеры в конфиг, если их там нет
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

        // 3. Удаляем из конфига те, которых больше нет в системе (через Iterator)
        Iterator<AppConfig.CameraConfig> cfgIt = config.cameras.iterator();
        while (cfgIt.hasNext()) {
            if (cfgIt.next().id >= count) {
                cfgIt.remove();
                changed = true;
            }
        }

        if (changed) {
            config.save(this);
        }

        // Перезапускаем те, которые помечены как active
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.active && !activeCameras.containsKey(c.id)) {
                startCamera(c.id);
            }
        }
        updateNotification(buildNotif("Активно камер: " + activeCameras.size() + " из " + count));
    }

    public synchronized void updateCameraConfig(int id, int w, int h, int rot, int fps, boolean gray, boolean active) {
        AppConfig.CameraConfig cc = null;
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.id == id) { cc = c; break; }
        }
        if (cc == null) return;

        boolean resChanged = (cc.width != w || cc.height != h);
        cc.width = w;
        cc.height = h;
        cc.rotation = rot;
        cc.fps = fps;
        cc.grayscale = gray;
        cc.active = active;

        boolean wasActive = activeCameras.containsKey(id);

        if (active && !wasActive) {
            startCamera(id);
        } else if (!active && wasActive) {
            stopCamera(id);
        } else if (active && wasActive) {
            if (resChanged) {
                broadcaster.endStream(id);
            }
            activeCameras.get(id).updateSettings(w, h, rot, fps, gray);
            config.save(this);
        } else {
            config.save(this);
        }
    }

    public synchronized boolean startCamera(int id) {
        if (activeCameras.containsKey(id)) return true;

        AppConfig.CameraConfig cc = null;
        for (AppConfig.CameraConfig c : config.cameras) {
            if (c.id == id) { cc = c; break; }
        }
        if (cc == null) return false;

        try { Thread.sleep(500); } catch (Exception ignored) {}

        CameraController controller = new CameraController(id, broadcaster);
        if (controller.start(cc.width, cc.height, cc.rotation, cc.fps, cc.grayscale)) {
            activeCameras.put(id, controller);
            updateNotification(buildNotif("Активно камер: " + activeCameras.size()));
            return true;
        }
        return false;
    }

    public synchronized boolean stopCamera(int id) {
        CameraController controller = activeCameras.remove(id);
        if (controller != null) controller.stop();
        broadcaster.endStream(id);
        updateNotification(buildNotif("Активно камер: " + activeCameras.size()));
        return true;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        for (CameraController cc : activeCameras.values()) cc.stop();
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    public AppConfig getConfig() { return config; }
    public FrameBroadcaster getBroadcaster() { return broadcaster; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "RoverCam", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
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