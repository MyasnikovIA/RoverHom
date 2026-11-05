package ru.miacomsoft.roverhom;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import ru.miacomsoft.roverhom.handlers.*;

public class MJPEGServer {
    private HttpServer server;
    private final Context ctx;
    private final StreamService svc;

    public MJPEGServer(Context ctx, StreamService svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    public void start(int port) throws Exception {
        stop();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

// В методе start() класса MJPEGServer:
        server.createContext("/", new RootHandler(ctx));
        server.createContext("/api/config", new ApiConfigHandler(svc));
        server.createContext("/api/cameras", new ApiCamerasHandler(svc)); // GET список
        server.createContext("/api/cameras/rescan", new ApiCameraControlHandler(svc)); // POST пересканировать
        server.createContext("/api/camera/", new ApiCameraControlHandler(svc));
        server.createContext("/api/stream", new ApiStreamHandler(svc.getBroadcaster()));
        server.createContext("/Cam", new ApiStreamHandler(svc.getBroadcaster()));
        server.createContext("/api/snapshot.jpg", new ApiSnapshotHandler(svc.getBroadcaster()));

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}