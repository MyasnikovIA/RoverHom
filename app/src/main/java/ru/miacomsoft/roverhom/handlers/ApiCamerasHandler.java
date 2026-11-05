package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ru.miacomsoft.roverhom.AppConfig;
import ru.miacomsoft.roverhom.StreamService;

public class ApiCamerasHandler implements HttpHandler {
    private final StreamService svc;

    public ApiCamerasHandler(StreamService svc) { this.svc = svc; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        JSONObject res = new JSONObject();
        res.put("port", svc.getConfig().port);

        JSONArray arr = new JSONArray();
        for (AppConfig.CameraConfig c : svc.getConfig().cameras) {
            JSONObject jc = new JSONObject();
            jc.put("id", c.id);
            jc.put("width", c.width);
            jc.put("height", c.height);
            jc.put("rotation", c.rotation);
            jc.put("fps", c.fps);
            jc.put("grayscale", c.grayscale);
            jc.put("active", c.active);
            arr.put(jc);
        }
        res.put("cameras", arr);

        byte[] data = res.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        OutputStream out = ex.getResponseBody();
        out.write(data);
        out.close();
    }
}