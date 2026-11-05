# RoverHom
# README.md для проекта RoverCam Multi-Stream
# RoverCam Multi-Stream

Android-приложение, превращающее смартфон, планшет или TV-приставку
в автономный многокамерный MJPEG-сервер видеонаблюдения с веб-интерфейсом.

Трансляция и управление камерами — через обычный браузер в локальной сети.
Интернет и внешние сервисы не требуются.

---

## Содержание

- [Возможности](#возможности)
- [Требования](#требования)
- [Установка и сборка](#установка-и-сборка)
- [Быстрый старт](#быстрый-старт)
- [Веб-интерфейс](#веб-интерфейс)
- [HTTP API](#http-api)
- [Конфигурация](#конфигурация)
- [Архитектура](#архитектура)
- [Работа с USB-камерами](#работа-с-usb-камерами)
- [Ограничения железа](#ограничения-железа)
- [Диагностика и отладка](#диагностика-и-отладка)
- [Структура проекта](#структура-проекта)
- [Лицензия](#лицензия)

---

## Возможности

- **Мультикамерная трансляция.** Одновременно или поочерёдно —
  в зависимости от возможностей Camera HAL конкретного устройства.
- **MJPEG-стриминг** по HTTP (`multipart/x-mixed-replace`),
  воспроизводится любым браузером без плагинов и JS-библиотек.
- **Веб-интерфейс** для старта/остановки камер, выбора разрешения,
  поворота (0/90/180/270°), FPS и чёрно-белого режима.
- **Автоматический fallback.** Если HAL отказывается открыть вторую камеру
  (`Too many cameras already open`), приложение само останавливает текущую,
  запускает новую и показывает пользователю информационную плашку.
- **Hot-plug USB UVC.** Автоматический запрос `UsbManager.requestPermission()`
  и рескан шины при подключении/отключении USB-камер.
- **Работа в фоне.** Foreground Service с уведомлением — сервер продолжает
  работать при свёрнутом приложении или выключенном экране.
- **Свободный порт автоматически.** При занятости `8080` сервер
  перебирает `8081..8099`, фактический порт сохраняется в `SharedPreferences`
  и используется WebView'ом.
- **Единый APK без зависимостей.** Встроенная реализация
  `com.sun.net.httpserver` — ничего не нужно доустанавливать.

---

## Требования

| Параметр | Значение |
|---|---|
| Android | API 24 (Android 7.0) и выше |
| Разрешения | `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` |
| Опционально | `android.hardware.usb.host` (для UVC-камер) |
| Сборка | Android Studio + AGP 7.x, Java 8 |

---

## Установка и сборка

```bash
git clone https://github.com/<ваш-логин>/RoverCam.git
cd RoverCam
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

При первом запуске приложение попросит разрешение на камеру.
После этого поднимает HTTP-сервер и открывает WebView на
`http://127.0.0.1:<port>/`.

IP-адрес устройства в локальной сети показывается стандартным
уведомлением Android и в приложении.

---

## Быстрый старт

1. Установите APK, дайте разрешение на камеру.
2. Узнайте IP устройства в локальной сети (например, `192.168.1.42`).
3. Откройте в браузере любого другого устройства: `http://192.168.1.42:8080/`.
4. В карточке камеры выберите разрешение, поворот, FPS —
   нажмите **▶ Старт**. Видео появится прямо в браузере.

---

## Веб-интерфейс

Каждая камера — карточка с настройками и областью просмотра.

| Элемент | Описание |
|---|---|
| **▶ Старт** | Запуск камеры. Если HAL занят, приложение попробует освободить его автоматически. |
| **■ Стоп** | Остановка и освобождение камеры. |
| **Разрешение** | 160×120, 320×240, 640×480, 1280×720 (зависит от поддержки камеры). |
| **Поворот** | 0°, 90°, 180°, 270°. Поворот выполняется на сервере, до сжатия в JPEG. |
| **FPS** | 1–30. Снижение FPS уменьшает нагрузку на CPU и USB-шину. |
| **Ч/Б** | Grayscale-режим — обнуление U/V-плоскостей NV21 перед кодированием. |

Плашка сверху сообщает о результатах фоновых операций
(«Камера N запущена», «Одновременная работа не поддерживается», …)
и исчезает автоматически через 6 секунд.

---

## HTTP API

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/` | Web UI (`assets/www/index.html`) |
| `GET` | `/Cam{N}.mjpg` | MJPEG-поток камеры `N` (`multipart/x-mixed-replace`) |
| `GET` | `/api/stream?id=N` | Альтернативная форма MJPEG (query-параметр) |
| `GET` | `/api/snapshot.jpg?id=N` | Одиночный JPEG-кадр (или 503, если кадров нет) |
| `GET` | `/api/cameras` | Список камер и текущий порт сервера |
| `GET` | `/api/config` | Полный `config.json` |
| `POST` | `/api/config` | Обновление настроек камер (см. ниже) |
| `POST` | `/api/camera/start?id=N` | Запуск камеры |
| `POST` | `/api/camera/stop?id=N` | Остановка камеры |
| `POST` | `/api/cameras/rescan` | Принудительный ре-скан USB-шины |

### Пример `POST /api/config`

```json
{
  "port": 8080,
  "cameras": [
    {
      "id": 0,
      "width": 320,
      "height": 240,
      "rotation": 0,
      "fps": 5,
      "grayscale": false,
      "active": true
    }
  ]
}
```

Ответ: `{"ok": true}` или `{"ok": false}` —
если HAL не смог открыть камеру, `active` откатывается в `false`,
а UI показывает пользователю подсказку.

---

## Конфигурация

Файл `config.json` хранится во внутреннем хранилище приложения
(`Context.getFilesDir()`). Через API его можно читать
и перезаписывать. Структура:

```json
{
  "port": 8080,
  "cameras": [
    { "id": 0, "width": 160, "height": 120, "rotation": 0,
      "fps": 5, "grayscale": false, "active": false }
  ]
}
```

Дополнительно в `SharedPreferences` (namespace `rover_prefs`)
хранится **фактический** порт (`current_port`), если сервер был вынужден
сдвинуться с `8080` из-за `EADDRINUSE`.

---

## Архитектура

```
┌────────────────────────────────────────────────────────────┐
│                        MainActivity                        │
│  запрос CAMERA → startForegroundService(StreamService)     │
│  ждёт готовности порта из SharedPreferences → WebView      │
└───────────────────────────┬────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│                       StreamService                        │
│  - держит конфиг (AppConfig)                               │
│  - регистрирует USB-receiver                               │
│  - стартует MJPEGServer                                    │
│  - syncCameras(): пересчёт активных камер                  │
└─────┬──────────────────────────────────────────────┬───────┘
      │                                              │
      ▼                                              ▼
┌───────────────┐                          ┌──────────────────┐
│ CameraController │  NV21 → JPEG           │ MJPEGServer       │
│  - Camera1 API   │  ────────────────────► │  - HttpServer     │
│  - SurfaceTexture│                        │  - Executor       │
│  - HandlerThread │                        │                  │
└───────────────┬──┘                        └──────┬───────────┘
                │                                  │
                │ broadcaster.broadcast(id, jpeg)  │
                ▼                                  │
          ┌──────────────────────┐                 │
          │  FrameBroadcaster    │  subscribe()    │
          │  последние кадры     │ ◄───────────────┤
          │  + pub/sub           │                 │
          └──────────────────────┘                 │
                                                   ▼
                                     ┌────────────────────────┐
                                     │  ApiStreamHandler      │
                                     │  multipart/x-mixed-... │
                                     └────────────────────────┘
```

### Ключевые компоненты

- **`StreamService`** — главный сервис. Управляет жизненным циклом
  сервера, USB-событиями и картой активных `CameraController`.
- **`CameraController`** — обёртка над `android.hardware.Camera` (API 1).
  Открывает камеру, настраивает параметры, получает NV21-кадры через
  `setPreviewCallbackWithBuffer`, сжимает в JPEG на отдельном
  `HandlerThread`.
- **`FrameBroadcaster`** — потокобезопасный pub/sub. Хранит последний
  кадр каждой камеры и рассылает новые JPEG всем `FrameListener`.
- **`MJPEGServer`** — HTTP-сервер с автоматическим перебором портов.
- **`AppConfig`** — парсинг/сериализация `config.json`.
- **`RootHandler`** — отдаёт статику из `assets/www`.
- **`ApiConfigHandler`**, **`ApiCameraControlHandler`**,
  **`ApiStreamHandler`**, **`ApiSnapshotHandler`** — REST-эндпоинты.
- **`index.html`** — SPA на чистом JS; управляет эксклюзивным запуском
  камер, обновляет карточки без перезагрузки страницы.

---

## Работа с USB-камерами

### Разрешения и манифест

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

<service
    android:name=".StreamService"
    android:foregroundServiceType="camera"
    android:exported="false" />
```

### Запрос прав `UsbManager`

`StreamService.onCreate()` регистрирует `BroadcastReceiver`
на `ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED`
и `ACTION_USB_PERMISSION`. При подключении камеры вызывается
`UsbManager.requestPermission()`. После выдачи разрешения через
1.5 / 4 / 8 секунд производится повторный `syncCameras()` —
UVC-камера может появиться в `Camera.getNumberOfCameras()`
не сразу, а с задержкой.

### Требования к железу

- Наличие USB-host (обычно есть на TV-боксах, нет на большинстве смартфонов).
- Драйвер `uvcvideo` в ядре (`ls /sys/module | grep uvc`).
- HAL, пробрасывающий UVC в `android.hardware.Camera`
  (в `dumpsys media.camera` должна быть строка
  `Camera Provider HAL external/0`).
- Для двух и более камер — **USB-хаб с внешним питанием ≥ 2 А на порт**.

---

## Ограничения железа

Ниже — реальные кейсы, с которыми приложение сталкивалось при тестировании.

### Samsung SM-J710F (Android 8.1)

Camera HAL разрешает открыть только **одну** камеру за раз.
При попытке открыть вторую — `Camera.open()` кидает
`Status(-8): 'Too many cameras already open'`.
Приложение ловит эту ошибку, автоматически останавливает текущую
камеру, ждёт ~2 с (HAL небыстрый) и повторяет запуск.
В итоге — «одна камера, но переключается по клику Старт».

### Rockchip RK3318 (H96 Max, Android 10)

Прошивка содержит `Camera Provider HAL external/0`, который
корректно пробрасывает UVC-камеры. Две камеры **открываются
одновременно** при условии достаточного питания USB.

Частые проблемы:
- **Хабы `1a40:0201` (Terminus FE)** без внешнего питания не тянут
  UVC-камеру — enumeration проваливается, в `lsusb` её нет,
  в `dmesg` видны `error -71`. Лечение: активный USB-хаб.
- **USB 3.0-порт часто совмещён с OTG**. Если он в device-режиме
  (для ADB) — камера в нём не заработает. Нужно втыкать
  в чёрный USB 2.0-порт.
- **Большие номера устройств** в `lsusb` (например `Device 034`)
  — признак повторяющихся неудачных enumeration,
  чаще всего из-за питания.

### Смартфоны с проприетарным HAL

Многие вендоры (не только Samsung) блокируют UVC-камеры через
`android.hardware.Camera`. Проверка:

```bash
adb shell dumpsys media.camera | grep "Number of camera"
```

Если после подключения UVC-камеры число не меняется —
HAL её не пробрасывает. Единственный выход — использовать
`UsbDeviceConnection` + кастомную UVC-библиотеку (например,
`serenegiant/UVCCamera`) вместо `Camera.open()`.

---

## Диагностика и отладка

### Полезные логи

```bash
adb logcat -s StreamService CameraController MJPEGServer
```

Ключевые строки:

```
I/StreamService: syncCameras: Camera1=2 Camera2=2 USB=0 USBVideo=0 active=0
I/StreamService: === USB devices: 0 ===
I/StreamService: updateCameraConfig: camera 1 stopped, config saved
I/CameraController: Trying to open Camera 0, attempt 1
E/CameraController: Camera 0 cannot be opened: hardware limit reached
```

### Проверка USB на устройстве

```bash
adb shell lsusb
adb shell ls /sys/module | grep -E 'uvc|videodev'
adb shell "cat /proc/config.gz 2>/dev/null | zcat | grep -i uvc"
adb shell dumpsys usb
adb shell dumpsys media.camera | grep "Number of camera"
adb shell dmesg | grep -iE 'usb|uvc|error'
```

### Проверка HTTP-сервера

```bash
curl http://<device-ip>:8080/api/cameras
curl http://<device-ip>:8080/api/snapshot.jpg?id=0 -o snap.jpg
```

---

## Структура проекта

```
app/src/main/java/ru/miacomsoft/roverhom/
├── MainActivity.java              // Activity + WebView-контейнер
├── StreamService.java             // Foreground Service
├── AppConfig.java                 // конфиг камер и порта
├── CameraController.java          // обёртка над Camera1
├── FrameBroadcaster.java          // pub/sub кадров
├── FrameProcessor.java            // интерфейс пост-обработки
├── MJPEGServer.java               // HTTP-сервер, авто-выбор порта
├── handlers/
│   ├── RootHandler.java
│   ├── ApiConfigHandler.java
│   ├── ApiCamerasHandler.java
│   ├── ApiCameraControlHandler.java
│   ├── ApiStreamHandler.java
│   └── ApiSnapshotHandler.java
└── com/sun/net/httpserver/        // встроенная реализация HTTP
    ├── HttpServer.java
    ├── HttpContext.java
    ├── HttpExchange.java
    ├── HttpHandler.java
    ├── Headers.java
    ├── HttpsServer.java
    └── HttpsConfigurator.java

app/src/main/assets/www/
└── index.html                     // Web UI
```

---

## Лицензия

MIT. См. `LICENSE` в корне репозитория.

