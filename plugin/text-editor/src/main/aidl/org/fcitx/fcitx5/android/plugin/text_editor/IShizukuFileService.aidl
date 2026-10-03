package org.fcitx.fcitx5.android.plugin.text_editor;

interface IShizukuFileService {
    void destroy() = 16777114;
    long fileSize(String path) = 1;
    byte[] read(String path, long offset, int length) = 2;
    String[] list(String path) = 3;
    long lastModified(String path) = 4;
    void truncate(String path) = 5;
    void write(String path, long offset, in byte[] data) = 6;
}
