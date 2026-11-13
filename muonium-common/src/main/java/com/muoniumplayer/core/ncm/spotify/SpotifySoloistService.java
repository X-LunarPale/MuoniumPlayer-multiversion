package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the official Soloist process and its loopback WebSocket; never exchanges OAuth tokens. */
public final class SpotifySoloistService {
    public static final String DOWNLOAD_URL = "https://developer.spotify.com/documentation/soloist/reference/downloads-and-updates";
    public static final String KEY_URL = "https://developer.spotify.com/dashboard";
    public static final String DEVICE_NAME = "MuoniumPlayer Soloist";
    private static final Object LOCK = new Object();
    private static final Object SEND_LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Spotify Soloist Lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    // A slow version/process/socket probe must not delay saving an explicit disconnect at exit.
    private static final ExecutorService PREFERENCES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Spotify Soloist Preferences");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static volatile SpotifySoloistState state = new SpotifySoloistState();
    private static volatile WebSocket socket;
    private static Process process;
    private static Process stoppingProcess;
    private static long generation;
    private static boolean reconnectDisabled;
    private static FileChannel lockFile;
    private static FileLock processLock;
    private static volatile boolean starting;
    private static volatile String message = "填写程序路径与 API Key 后启动配对";
    private static volatile String problem = "";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            disconnect();
            PREFERENCES.shutdown();
            WORKER.shutdown();
            try {
                PREFERENCES.awaitTermination(3, TimeUnit.SECONDS);
                WORKER.awaitTermination(3, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "Soloist Shutdown"));
    }

    private SpotifySoloistService() { }

    public static boolean isPaired() { return socket != null && state.paired(); }
    public static boolean isRunning() { return socket != null; }
    public static boolean isStarting() { return starting; }

    public static String statusMessage() {
        if (!problem.isEmpty()) return problem;
        if (isPaired()) return "Soloist 已配对 · 播放与歌词同步已就绪";
        return message;
    }

    public static SpotifyConnectService.PlaybackState playback() {
        return socket == null ? null : state.snapshot(System.currentTimeMillis());
    }

    /** Restores only a saved, enabled setup; all disk/native/process work stays on WORKER. */
    public static void restoreSavedSession() {
        final long requested;
        synchronized (LOCK) { requested = generation; }
        WORKER.execute(() -> {
            synchronized (LOCK) {
                if (requested != generation || starting || socket != null || reconnectDisabled) return;
                if (SpotifySoloistStore.reconnectOnStartup()) start(SpotifySoloistStore.executable(), "");
            }
        });
    }

    /** Disk, native key protection, process startup and socket connection all run off the UI thread. */
    public static void start(String executable, String apiKey) {
        final long attempt;
        synchronized (LOCK) {
            if (starting) return;
            // The setup button may be pressed after automatic restore. Reuse that connection.
            if (socket != null && (apiKey == null || apiKey.isBlank())
                    && SpotifySoloistStore.executable().equals(executable == null ? "" : executable.trim())) return;
            reconnectDisabled = false;
            disconnectLocked();
            attempt = ++generation;
            starting = true;
            message = "正在校验并启动 Soloist…";
        }
        WORKER.execute(() -> {
            try {
                synchronized (LOCK) {
                    if (attempt != generation) return;
                    SpotifySoloistStore.save(executable, apiKey);
                }
                String program = SpotifySoloistStore.executable();
                verifyProgram(program);
                Path data = ConfigPaths.SPOTIFY_SOLOIST_DATA.toPath().toAbsolutePath();
                Path cache = ConfigPaths.SPOTIFY_SOLOIST_CACHE.toPath().toAbsolutePath();
                Process previous;
                synchronized (LOCK) { previous = stoppingProcess; }
                if (previous != null && !previous.waitFor(3, TimeUnit.SECONDS)) {
                    previous.destroyForcibly();
                    if (!previous.waitFor(2, TimeUnit.SECONDS)) throw new IOException("旧 Soloist 进程尚未退出，请稍后重试");
                }
                synchronized (LOCK) {
                    if (attempt != generation) return;
                    Files.createDirectories(data);
                    Files.createDirectories(cache);
                    lockFile = FileChannel.open(data.resolve("muonium.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    processLock = lockFile.tryLock();
                    if (processLock == null) throw new IOException("另一个游戏实例正在使用 Soloist，请先断开它");
                }
                // A crashed JVM releases its file lock without necessarily stopping its child.
                // Probe outside LOCK so disconnect and UI status reads never wait on network I/O.
                checkEndpointInactive(data);
                synchronized (LOCK) {
                    if (attempt != generation) return;
                    Files.deleteIfExists(data.resolve("ws.port"));
                    Files.deleteIfExists(data.resolve("ws.addr"));
                    process = new ProcessBuilder(List.of(program, "-n", DEVICE_NAME,
                            "-k", SpotifySoloistStore.key(), "-D", data.toString(), "-C", cache.toString(),
                            "-z", "512", "-w", "127.0.0.1:0"))
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline) {
                    synchronized (LOCK) {
                        if (attempt != generation) return;
                        if (!process.isAlive()) throw new IOException(process.exitValue() == 10
                                ? "Soloist 程序已过期，请从 Spotify 下载新版" : "Soloist 启动失败，请检查 API Key 与音频设备");
                    }
                    Path portFile = data.resolve("ws.port");
                    if (Files.isRegularFile(portFile)) {
                        try {
                            int port = Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).trim());
                            if (port < 1 || port > 65535) throw new NumberFormatException();
                            connect(port, attempt);
                            awaitSavedPairing(attempt);
                            return;
                        } catch (NumberFormatException incompletePortFile) {
                            // Soloist may still be writing its port file; retry within the startup deadline.
                        }
                    }
                    Thread.sleep(200);
                }
                throw new IOException("Soloist 未在 8 秒内开放本地控制端口，请检查程序与 API Key");
            } catch (Exception error) {
                synchronized (LOCK) {
                    if (attempt != generation) return;
                    disconnectLocked();
                    message = error instanceof IOException ? error.getMessage() : "Soloist 连接失败，请重新启动配对";
                }
            }
        });
    }

    static void checkEndpointInactive(Path data) throws IOException {
        Path portFile = data.resolve("ws.port");
        if (!Files.isRegularFile(portFile)) return;
        if (Files.size(portFile) > 64) throw new IOException("Soloist 控制端口文件异常，请检查本地配对资料");
        final int port;
        try {
            port = Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException incompleteFile) {
            return;
        }
        if (port < 1 || port > 65535) return;
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 750);
        } catch (ConnectException noListener) {
            return;
        }
        // Keep unknown/live endpoints intact. A PID alone cannot prove that we own a process.
        throw new IOException("检测到仍在运行的 Soloist 控制端口，请先在任务管理器结束遗留 Soloist，再重新启动");
    }

    /** --version is the only probe; the API key is never included in probe output or diagnostics. */
    private static void verifyProgram(String executable) throws Exception {
        Path output = Files.createTempFile("muonium-soloist-version", ".txt");
        Process probe = null;
        try {
            probe = new ProcessBuilder(executable, "--version").redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!probe.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Soloist 版本检查超时，请检查程序路径");
            if (probe.exitValue() == 10) throw new IOException("Soloist 程序已过期，请下载新版");
            if (Files.size(output) > 16384 || probe.exitValue() != 0
                    || !Files.readString(output, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("soloist")) {
                throw new IOException("所选文件不是可运行的 Soloist 程序");
            }
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            ++generation;
            disconnectLocked();
            message = "Soloist 已断开；配对资料仍保留在本地";
        }
    }

    /** User action, unlike shutdown: keep credentials but do not reconnect at the next game start. */
    public static void disconnectAndDisableReconnect() {
        synchronized (LOCK) {
            disconnect();
            reconnectDisabled = true;
            PREFERENCES.execute(() -> {
                synchronized (LOCK) {
                    // A later explicit start owns the latest preference and will save true.
                    if (!reconnectDisabled) return;
                    try { SpotifySoloistStore.disableReconnect(); }
                    catch (IOException error) { problem = "无法保存 Soloist 断开设置，请稍后重试"; }
                }
            });
        }
    }

    private static void disconnectLocked() {
        WebSocket old = socket;
        socket = null;
        if (old != null) old.abort();
        if (process != null) {
            process.destroy();
            Process stopping = process;
            stoppingProcess = stopping;
            CompletableStop.stopLater(stopping);
            process = null;
        }
        state = new SpotifySoloistState();
        problem = "";
        starting = false;
        try { if (processLock != null) processLock.release(); } catch (IOException ignored) { }
        try { if (lockFile != null) lockFile.close(); } catch (IOException ignored) { }
        processLock = null;
        lockFile = null;
        LOCK.notifyAll();
    }

    private static void connect(int port, long attempt) throws Exception {
        WebSocket connected = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://127.0.0.1:" + port), new Listener(attempt)).get(4, TimeUnit.SECONDS);
        synchronized (LOCK) {
            if (attempt != generation) { connected.abort(); return; }
            socket = connected;
            message = "正在恢复已保存的 Spotify 配对…";
        }
    }

    private static void awaitSavedPairing(long attempt) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (LOCK) {
            while (attempt == generation && socket != null && !state.paired()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(LOCK, remaining);
            }
            if (attempt != generation || socket == null) return;
            starting = false;
            message = state.paired() ? "Soloist 已自动恢复配对"
                    : "在同一局域网的 Spotify 设备列表中选择「" + DEVICE_NAME + "」完成首次配对";
        }
    }

    /** Exercises the real loopback protocol without starting Spotify or accessing credentials. */
    static void connectForTest(int port) throws Exception {
        long attempt;
        synchronized (LOCK) { disconnectLocked(); attempt = ++generation; }
        connect(port, attempt);
    }

    private static final class CompletableStop {
        static void stopLater(Process child) {
            java.util.concurrent.CompletableFuture.delayedExecutor(2500, TimeUnit.MILLISECONDS)
                    .execute(() -> { if (child.isAlive()) child.destroyForcibly(); });
        }
    }

    static JsonObject command(String name, String field, Number value) {
        JsonObject command = new JsonObject();
        command.addProperty("type", "command");
        command.addProperty("command", name);
        if (field != null) command.addProperty(field, value);
        return command;
    }

    public static void send(String name) throws IOException { send(command(name, null, null)); }
    public static void playTrack(String id) throws IOException {
        playTrack(id, null);
    }
    static void playTrack(String id, SpotifySoloistTrackWatch watch) throws IOException {
        JsonObject command = command("play", null, null);
        command.addProperty("uri", SpotifyMusicService.playableUri(id));
        synchronized (SEND_LOCK) {
            state.watch(watch, System.currentTimeMillis(), false);
            send(command);
        }
    }
    static void watchCurrent(SpotifySoloistTrackWatch watch) {
        state.watch(watch, System.currentTimeMillis(), true);
    }
    static void repeatOff() throws IOException {
        for (String name : new String[]{"set_repeat_track", "set_repeat_context"}) {
            JsonObject command = command(name, null, null);
            command.addProperty("enabled", false);
            send(command);
        }
    }
    public static void seek(long milliseconds) throws IOException { send(command("seek", "position_ms", Math.max(0, milliseconds))); }
    public static void volume(int percent) throws IOException { send(command("set_volume", "volume", Math.max(0, Math.min(100, percent)))); }

    private static void send(JsonObject command) throws IOException {
        synchronized (SEND_LOCK) {
            WebSocket current = socket;
            if (current == null || !state.paired()) throw new IOException("Soloist 尚未配对，请在 Spotify 设备列表中选择它");
            try { current.sendText(command.toString(), true).get(3, TimeUnit.SECONDS); }
            catch (Exception error) { throw new IOException("Soloist 控制连接已断开，请重新连接"); }
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder text = new StringBuilder();
        Listener(long attempt) { this.attempt = attempt; }

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence fragment, boolean last) {
            synchronized (LOCK) {
                if (attempt != generation) { webSocket.abort(); return null; }
                if (text.length() + fragment.length() > 1_048_576) {
                    ++generation;
                    disconnectLocked();
                    message = "Soloist 响应过大，连接已关闭";
                    return null;
                }
                text.append(fragment);
                if (last) {
                    try {
                        JsonObject event = JsonParser.parseString(text.toString()).getAsJsonObject();
                        if ("error".equals(SpotifySoloistState.text(event, "type"))) {
                            problem = "Soloist 拒绝了控制命令，请检查配对与播放状态";
                        } else {
                            state.accept(event, System.currentTimeMillis());
                            LOCK.notifyAll();
                            problem = "";
                        }
                    } catch (RuntimeException invalidEvent) {
                        problem = "Soloist 返回了无法识别的状态，请检查程序版本";
                    }
                    text.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            lost();
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { lost(); }

        private void lost() {
            synchronized (LOCK) {
                if (attempt != generation) return;
                ++generation;
                disconnectLocked();
                message = "Soloist 已停止，请重新启动；退出码 10 表示需要下载新版";
            }
        }
    }
}
