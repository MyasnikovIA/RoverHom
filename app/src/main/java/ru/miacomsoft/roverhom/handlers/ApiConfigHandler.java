package ru.miacomsoft.roverhom.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import ru.miacomsoft.roverhom.AppConfig;
import ru.miacomsoft.roverhom.StreamService;

public class ApiConfigHandler implements HttpHandler {
    private final StreamService svc;

    public ApiConfigHandler(StreamService svc) { this.svc = svc; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        AppConfig cfg = svc.getConfig();

        if ("GET".equals(method)) {
            JSONObject res = new JSONObject();
            res.put("port", cfg.port);
            JSONArray arr = new JSONArray();
            for (AppConfig.CameraConfig c : cfg.cameras) {
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
            sendJson(ex, res.toString());
        } else if ("POST".equals(method)) {
            InputStream is = ex.getRequestBody();
            byte[] buf = new byte[8192];
            int n = is.read(buf);
            if (n > 0) {
                JSONObject req = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
                int port = req.optInt("port", cfg.port);
                cfg.port = port;

                JSONArray arr = req.optJSONArray("cameras");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject jc = arr.getJSONObject(i);
                        svc.updateCameraConfig(
                                jc.getInt("id"),
                                jc.optInt("width", 320),
                                jc.optInt("height", 240),
                                jc.optInt("rotation", 0),
                                jc.optInt("fps", 10),
                                jc.optBoolean("grayscale", false),
                                jc.optBoolean("active", false)
                        );
                    }
                }
                cfg.save(svc);
            }
            sendJson(ex, "{\"ok\":true}");
        }
    }

    private void sendJson(HttpExchange ex, String json) throws Exception {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        OutputStream out = ex.getResponseBody();
        out.write(data);
        out.close();
    }
}