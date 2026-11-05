package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ru.miacomsoft.roverhom.StreamService;

public class ApiCameraControlHandler implements HttpHandler {
    private final StreamService svc;

    public ApiCameraControlHandler(StreamService svc) { this.svc = svc; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        int camId = 0;

        if (query != null && query.contains("id=")) {
            try { camId = Integer.parseInt(query.split("id=")[1].split("&")[0]); } catch (Exception ignored) {}
        }

        boolean ok = false;
        if ("/api/camera/start".equals(path)) {
            ok = svc.startCamera(camId);
        } else if ("/api/camera/stop".equals(path)) {
            ok = svc.stopCamera(camId);
        } else if ("/api/cameras/rescan".equals(path)) {
            svc.rescanCameras(); // Теперь метод существует и компилируется
            ok = true;
        }

        String res = "{\"ok\":" + ok + "}";
        byte[] data = res.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, data.length);
        OutputStream out = ex.getResponseBody();
        out.write(data);
        out.close();
    }
}