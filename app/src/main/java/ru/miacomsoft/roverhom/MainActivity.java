package ru.miacomsoft.roverhom;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
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
    private int port;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Программное создание интерфейса: TextView для загрузки и WebView
        android.widget.FrameLayout layout = new android.widget.FrameLayout(this);
        layout.setBackgroundColor(0xFF101418); // Темный фон

        webView = new WebView(this);
        webView.setVisibility(View.GONE); // Скрыт до запуска сервера
        layout.addView(webView);

        loadingText = new TextView(this);
        loadingText.setText("Запуск сервера...");
        loadingText.setTextColor(0xFF66CCFF);
        loadingText.setTextSize(18);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = android.view.Gravity.CENTER;
        loadingText.setLayoutParams(lp);
        layout.addView(loadingText);

        setContentView(layout);

        @SuppressLint("SetJavaScriptEnabled")
        WebView dummy = new WebView(this); // Заглушка для компилятора
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        // Получаем порт из сохраненного конфига
        port = AppConfig.load(this).port;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
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
            try {
                // Пытаемся подключиться к локальному порту
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                socket.close();

                // Если успешно - обновляем UI в главном потоке
                handler.post(this::onServerReady);
            } catch (Exception e) {
                // Если не удалось - ждем 1 секунду и пробуем снова
                handler.postDelayed(this::waitForServer, 1000);
            }
        }).start();
    }

    private void onServerReady() {
        if (serverConnected) return;
        serverConnected = true;

        loadingText.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        // Загружаем страницу
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
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