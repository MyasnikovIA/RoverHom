package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.OutputStream;

import ru.miacomsoft.roverhom.FrameBroadcaster;

public class ApiSnapshotHandler implements HttpHandler {
    private final FrameBroadcaster broadcaster;

    public ApiSnapshotHandler(FrameBroadcaster b) { this.broadcaster = b; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        String query = ex.getRequestURI().getQuery();
        int camId = 0;
        if (query != null && query.contains("id=")) {
            try { camId = Integer.parseInt(query.split("id=")[1].split("&")[0]); } catch (Exception ignored) {}
        }

        byte[] jpeg = broadcaster.getLastFrame(camId);
        if (jpeg != null) {
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, jpeg.length);
            OutputStream out = ex.getResponseBody();
            out.write(jpeg);
            out.close();
        } else {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        }
    }
}