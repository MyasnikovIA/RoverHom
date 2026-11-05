package ru.miacomsoft.roverhom.processing;

public interface FrameProcessor {
    /**
     * Вызывается при получении нового кадра.
     * @param nv21 Сырые данные кадра (NV21)
     * @param width Ширина
     * @param height Высота
     * @return Модифицированные данные (NV21) для дальнейшей кодировки в JPEG
     */
    byte[] process(byte[] nv21, int width, int height);
}