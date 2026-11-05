package ru.miacomsoft.roverhom;

import android.content.Context;
import android.util.Log;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import ru.miacomsoft.roverhom.handlers.ApiCameraControlHandler;
import ru.miacomsoft.roverhom.handlers.ApiCamerasHandler;
import ru.miacomsoft.roverhom.handlers.ApiConfigHandler;
import ru.miacomsoft.roverhom.handlers.ApiSnapshotHandler;
import ru.miacomsoft.roverhom.handlers.ApiStreamHandler;
import ru.miacomsoft.roverhom.handlers.RootHandler;

public class MJPEGServer {
    private static final String TAG = "MJPEGServer";
    private static final int PORT_RANGE = 20; // пробуем preferredPort .. preferredPort+19

    private HttpServer server;
    private final Context ctx;
    private final StreamService svc;
    private int actualPort = -1;

    public MJPEGServer(Context ctx, StreamService svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    public int getPort() { return actualPort; }

    public void start(int preferredPort) throws Exception {
        stop();

        Exception lastEx = null;
        for (int offset = 0; offset < PORT_RANGE; offset++) {
            int port = preferredPort + offset;
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                actualPort = port;
                Log.i(TAG, "Server bound to port " + port);

                server.createContext("/", new RootHandler(ctx));
                server.createContext("/api/config", new ApiConfigHandler(svc));
                server.createContext("/api/cameras", new ApiCamerasHandler(svc));
                server.createContext("/api/cameras/rescan", new ApiCameraControlHandler(svc));
                server.createContext("/api/camera/", new ApiCameraControlHandler(svc));
                server.createContext("/api/stream", new ApiStreamHandler(svc));
                server.createContext("/Cam", new ApiStreamHandler(svc));
                server.createContext("/api/snapshot.jpg", new ApiSnapshotHandler(svc.getBroadcaster()));

                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return;
            } catch (IOException e) {
                Log.w(TAG, "Port " + port + " unavailable: " + e.getMessage());
                lastEx = e;
                server = null;
            }
        }
        throw lastEx != null
                ? lastEx
                : new IOException("No free port in range " + preferredPort + ".." + (preferredPort + PORT_RANGE - 1));
    }

    public void stop() {
        if (server != null) {
            try { server.stop(0); } catch (Exception ignored) {}
            server = null;
        }
        actualPort = -1;
    }
}