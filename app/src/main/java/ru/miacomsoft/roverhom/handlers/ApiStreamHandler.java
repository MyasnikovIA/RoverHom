package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import ru.miacomsoft.roverhom.FrameBroadcaster;

public class ApiStreamHandler implements HttpHandler {
    private final FrameBroadcaster broadcaster;

    public ApiStreamHandler(FrameBroadcaster b) { this.broadcaster = b; }

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
            while (true) {
                byte[] jpeg = queue.poll(5, TimeUnit.SECONDS);
                if (jpeg != null) {
                    if (jpeg.length == 0) {
                        // Сигнал остановки потока
                        break;
                    }
                    out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes());
                    out.write(jpeg);
                    out.write("\r\n".getBytes());
                    out.flush();
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