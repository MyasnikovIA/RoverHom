package ru.miacomsoft.roverhom.handlers;

import android.content.Context;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class RootHandler implements HttpHandler {
    private final Context ctx;
    public RootHandler(Context ctx) { this.ctx = ctx; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        try {
            InputStream is = ctx.getAssets().open("www" + path);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            byte[] data = buf.toByteArray();

            ex.getResponseHeaders().set("Content-Type", path.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream");
            ex.sendResponseHeaders(200, data.length);
            OutputStream out = ex.getResponseBody();
            out.write(data);
            out.close();
        } catch (Exception e) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        }
    }
}