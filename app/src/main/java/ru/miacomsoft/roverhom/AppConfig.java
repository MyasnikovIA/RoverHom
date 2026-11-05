package ru.miacomsoft.roverhom;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public int port = 8081;
    public List<CameraConfig> cameras = new ArrayList<>();

    public static class CameraConfig {
        public int id;
        public int width = 160;
        public int height = 120;
        public int rotation = 0;
        public int fps = 5;
        public boolean grayscale = false;
        public boolean active = false;

        public CameraConfig(int id) { this.id = id; }
    }

    private static final String FILE_NAME = "config.json";

    public static AppConfig load(Context ctx) {
        AppConfig cfg = new AppConfig();
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] data = readAll(in);
                in.close();
                JSONObject o = new JSONObject(new String(data, "utf-8"));
                cfg.port = o.optInt("port", cfg.port);

                JSONArray arr = o.optJSONArray("cameras");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        CameraConfig cc = new CameraConfig(c.optInt("id", i));
                        cc.width = c.optInt("width", 160);
                        cc.height = c.optInt("height", 120);
                        cc.rotation = c.optInt("rotation", 0);
                        cc.fps = c.optInt("fps", 5);
                        cc.grayscale = c.optBoolean("grayscale", false);
                        cc.active = c.optBoolean("active", false);
                        cfg.cameras.add(cc);
                    }
                }
            } catch (Exception e) {
                Log.e("AppConfig", "Load error", e);
            }
        }
        return cfg;
    }

    public void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("port", port);
            JSONArray arr = new JSONArray();
            for (CameraConfig c : cameras) {
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
            o.put("cameras", arr);

            File f = new File(ctx.getFilesDir(), FILE_NAME);
            FileOutputStream out = new FileOutputStream(f);
            out.write(o.toString(2).getBytes("utf-8"));
            out.close();
        } catch (Exception e) {
            Log.e("AppConfig", "Save error", e);
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}