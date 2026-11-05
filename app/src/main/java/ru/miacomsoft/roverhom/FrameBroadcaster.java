package ru.miacomsoft.roverhom;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FrameBroadcaster {
    public interface FrameListener {
        void onFrame(byte[] jpeg);
    }

    private final Map<Integer, List<FrameListener>> listeners = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> lastFrames = new ConcurrentHashMap<>();

    public void subscribe(int camId, FrameListener l) {
        listeners.computeIfAbsent(camId, k -> new CopyOnWriteArrayList<>()).add(l);
        byte[] last = lastFrames.get(camId);
        if (last != null && last.length > 0) {
            try { l.onFrame(last); } catch (Exception ignored) {}
        }
    }

    public void unsubscribe(int camId, FrameListener l) {
        List<FrameListener> list = listeners.get(camId);
        if (list != null) list.remove(l);
    }

    public byte[] getLastFrame(int camId) {
        return lastFrames.get(camId);
    }

    public void broadcast(int camId, byte[] jpeg) {
        lastFrames.put(camId, jpeg);
        List<FrameListener> list = listeners.get(camId);
        if (list != null) {
            for (FrameListener l : list) {
                try { l.onFrame(jpeg); } catch (Exception ignored) {}
            }
        }
    }

    // Новый метод: сообщаем всем клиентам, что поток остановлен
    public void endStream(int camId) {
        List<FrameListener> list = listeners.get(camId);
        if (list != null) {
            for (FrameListener l : list) {
                // Отправляем пустой массив как сигнал к закрытию
                try { l.onFrame(new byte[0]); } catch (Exception ignored) {}
            }
            listeners.remove(camId);
        }
        lastFrames.remove(camId);
    }
}