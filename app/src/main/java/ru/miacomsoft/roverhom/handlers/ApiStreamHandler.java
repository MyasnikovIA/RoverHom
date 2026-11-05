package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ru.miacomsoft.roverhom.FrameBroadcaster;
import ru.miacomsoft.roverhom.StreamService;

public class ApiStreamHandler implements HttpHandler {
    private final FrameBroadcaster broadcaster;
    private final StreamService svc;

    public ApiStreamHandler(StreamService svc) {
        this.svc = svc;
        this.broadcaster = svc.getBroadcaster();
    }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        int camId = 0;

        if (path.startsWith("/Cam")) {
            try {
                String num = path.substring(4, path.indexOf(".mjpg"));
                camId = Integer.parseInt(num);
            } catch (Exception e) {
                ex.sendResponseHeaders(400, -1); ex.close(); return;
            }
        } else {
            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("id=")) {
                try { camId = Integer.parseInt(query.split("id=")[1].split("&")[0]); } catch (Exception ignored) {}
            }
        }

        // ВАЖНО: Если камера не запущена, отдаем 404 (браузер покажет битую картинку вместо вечного ожидания)
        if (!svc.isCameraActive(camId)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(2);

        FrameBroadcaster.FrameListener listener = jpeg -> {
            queue.poll();
            queue.offer(jpeg);
        };

        broadcaster.subscribe(camId, listener);
        try {
            int timeouts = 0;
            while (true) {
                byte[] jpeg = queue.poll(2, TimeUnit.SECONDS);
                if (jpeg != null) {
                    if (jpeg.length == 0) break; // сигнал остановки
                    timeouts = 0;
                    out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes());
                    out.write(jpeg);
                    out.write("\r\n".getBytes());
                    out.flush();
                } else {
                    timeouts++;
                    // Если камера упала или нет кадров более 6 секунд - закрываем поток
                    if (!svc.isCameraActive(camId) || timeouts > 3) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Клиент отключился
        } finally {
            broadcaster.unsubscribe(camId, listener);
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}