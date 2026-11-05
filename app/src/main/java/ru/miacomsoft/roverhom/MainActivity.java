package ru.miacomsoft.roverhom;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private TextView loadingText;
    private boolean serverConnected = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.FrameLayout layout = new android.widget.FrameLayout(this);
        layout.setBackgroundColor(0xFF101418);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        layout.addView(webView);

        loadingText = new TextView(this);
        loadingText.setText("Запуск сервера...");
        loadingText.setTextColor(0xFF66CCFF);
        loadingText.setTextSize(18);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER;
        loadingText.setLayoutParams(lp);
        layout.addView(loadingText);

        setContentView(layout);

        @SuppressLint("SetJavaScriptEnabled")
        WebView dummy = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            startServer();
        }
    }

    private void startServer() {
        loadingText.setText("Запуск сервиса...");

        Intent svc = new Intent(this, StreamService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        waitForServer();
    }

    private void waitForServer() {
        if (serverConnected) return;

        new Thread(() -> {
            for (int attempt = 0; attempt < 120; attempt++) {
                SharedPreferences prefs =
                        getSharedPreferences(StreamService.PREFS, MODE_PRIVATE);
                int port = prefs.getInt(StreamService.KEY_PORT, -1);
                if (port > 0) {
                    try {
                        java.net.Socket s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                        s.close();
                        final int foundPort = port;
                        handler.post(() -> onServerReady(foundPort));
                        return;
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
            handler.post(() -> {
                loadingText.setText("Не удалось запустить сервер. Смотрите логи StreamService.");
                loadingText.setTextColor(0xFFFF4040);
            });
        }).start();
    }

    private void onServerReady(int port) {
        if (serverConnected) return;
        serverConnected = true;
        loadingText.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServer();
            } else {
                loadingText.setText("Ошибка: Нет разрешения на камеру!");
                loadingText.setTextColor(0xFFFF4040);
            }
        }
    }
}