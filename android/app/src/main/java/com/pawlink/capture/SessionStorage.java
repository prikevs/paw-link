package com.pawlink.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Deletion is restricted to one direct child session; never follows symbolic links. */
final class SessionStorage {
    static void deleteSession(File sessionsRoot, File session, File activeSession) throws IOException {
        File root = sessionsRoot.getCanonicalFile();
        File target = session.getCanonicalFile();
        if (Files.isSymbolicLink(session.toPath()) || !root.equals(target.getParentFile())) {
            throw new IOException("无效的采集记录路径");
        }
        if (activeSession != null && target.equals(activeSession.getCanonicalFile())) {
            throw new IOException("正在采集的记录不能删除");
        }
        if (!target.exists()) return;
        if (!target.isDirectory()) throw new IOException("采集记录不是目录");
        deleteTree(target);
    }

    private static void deleteTree(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取目录：" + file.getName());
            for (File child : children) deleteTree(child);
        }
        if (!file.delete() && Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("无法删除：" + file.getName());
        }
    }
}
