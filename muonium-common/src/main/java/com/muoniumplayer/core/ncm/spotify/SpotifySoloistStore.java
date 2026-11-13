package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Soloist settings are separate from OAuth. On Windows, the API key is protected by user DPAPI. */
public final class SpotifySoloistStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file = ConfigPaths.SPOTIFY_SOLOIST_CONFIG.toPath();
    private static Data data;
    private static String sessionKey = "";

    private SpotifySoloistStore() { }

    public static synchronized String executable() {
        load();
        return data.executable == null ? "" : data.executable;
    }

    public static synchronized boolean hasKey() {
        load();
        return !sessionKey.isEmpty() || (data.sealedKey != null && !data.sealedKey.isEmpty());
    }

    /** An empty key keeps the saved key; the clear action is explicit. */
    public static synchronized void save(String executable, String apiKey) throws IOException {
        load();
        String path = executable == null ? "" : executable.trim();
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1);
        }
        final Path selected;
        try {
            selected = Path.of(path);
        } catch (java.nio.file.InvalidPathException invalidPath) {
            throw new IOException("Soloist 程序路径无效，请选择 soloist.exe 或它所在的解压目录");
        }
        String programName = Platform.isWindows() ? "soloist.exe" : "soloist";
        Path program = Files.isDirectory(selected) ? selected.resolve(programName) : selected;
        if (!Files.isRegularFile(program)) {
            throw new IOException("未找到 " + programName + "，请填写程序文件或包含它的解压目录");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        String sealed = data.sealedKey;
        if (!key.isEmpty()) {
            if (!key.matches("[!-~]{8,512}")) throw new IOException("Soloist API Key 格式无效");
            if (Platform.isWindows()) {
                try {
                    sealed = Base64.getEncoder().encodeToString(
                            Crypt32Util.cryptProtectData(key.getBytes(StandardCharsets.UTF_8)));
                } catch (RuntimeException error) {
                    throw new IOException("Windows 无法加密保存 API Key，请稍后重试");
                }
            }
        }
        if (key.isEmpty() && !hasKey()) throw new IOException("请填写你自己的 Soloist API Key");
        data.executable = program.toAbsolutePath().toString();
        data.sealedKey = sealed;
        data.reconnectOnStartup = true;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        if (!key.isEmpty()) sessionKey = key;
    }

    /** Existing saved setups opt in; an explicit disconnect/logout opts out until the next start. */
    public static synchronized boolean reconnectOnStartup() {
        load();
        return !Boolean.FALSE.equals(data.reconnectOnStartup) && hasKey() && !executable().isBlank();
    }

    static synchronized void disableReconnect() throws IOException {
        load();
        data.reconnectOnStartup = false;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    static synchronized String key() throws IOException {
        load();
        if (!sessionKey.isEmpty()) return sessionKey;
        if (!hasKey()) throw new IOException("请填写 Soloist API Key");
        if (!Platform.isWindows()) throw new IOException("此平台的 API Key 仅保留本次游戏会话，请重新填写");
        try {
            sessionKey = new String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(data.sealedKey)),
                    StandardCharsets.UTF_8);
            return sessionKey;
        } catch (RuntimeException error) {
            throw new IOException("无法解密 Soloist API Key，请重新填写并保存");
        }
    }

    public static synchronized void clearKey() throws IOException {
        load();
        sessionKey = "";
        data.sealedKey = "";
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    private static void load() {
        if (data != null) return;
        data = new Data();
        if (!Files.isRegularFile(file)) return;
        try {
            Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (loaded != null) data = loaded;
        } catch (IOException | RuntimeException error) {
            System.err.println("[Music/Spotify] Soloist 配置无法读取，请在登录页重新保存");
        }
    }

    static synchronized void resetForTest(Path target) {
        file = target == null ? ConfigPaths.SPOTIFY_SOLOIST_CONFIG.toPath() : target;
        data = null;
        sessionKey = "";
    }

    private static final class Data {
        String executable = "";
        String sealedKey = "";
        Boolean reconnectOnStartup;
    }
}
