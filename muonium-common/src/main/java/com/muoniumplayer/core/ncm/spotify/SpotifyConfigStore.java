package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spotify 登录方式、开发者 Client ID 与市场代码。
 * 浏览器模式沿用 Folia 的公开桌面客户端身份与回环地址；开发者模式使用用户自建应用。
 * Client ID 是公开的 OAuth 标识，不是 client secret。
 */
public final class SpotifyConfigStore {

    public static final String ACCOUNTS_BASE = "https://accounts.spotify.com";
    public static final String AUTHORIZE_PATH = "/authorize";
    public static final String TOKEN_PATH = "/api/token";
    public static final String API_BASE = "https://api.spotify.com/v1";

    /** 开发者后台必须登记成一模一样的回调地址；端口见类注释。 */
    public static final String REDIRECT_URI = "http://127.0.0.1:47478/callback";
    public static final String BROWSER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    public static final String BROWSER_REDIRECT_URI = "http://127.0.0.1:5588/login";
    public enum LoginMode { BROWSER, DEVELOPER }

    /**
     * 授权要申请的 scopes。
     *
     * <p>曲库全是「读」：搜与播是匿名的元数据能力，登录换来的只有本人曲库
     * （歌单、喜欢的歌、账号展示资料）。写回曲库的操作不做，所以不申请
     * {@code playlist-modify-*} / {@code user-library-modify} —— scope 越少，
     * 用户在授权页上看到的警告越小。</p>
     *
     * <p>末尾两条是「客户端控制」（Spotify Connect）的入场券：{@code user-read-playback-state}
     * 读当前播放状态与设备列表，{@code user-modify-playback-state} 发播放/暂停/切歌/seek/音量
     * （仅对 Premium 账号生效，非 Premium 会在运行时收到 403）。改这份列表要记得：已登录用户
     * 的旧 token 不带新 scope，{@code SpotifyAuthStore.Session#hasPlaybackScopes()} 会判 false，
     * 设备面板据此提示重新授权。</p>
     */
    public static final String[] SCOPES = {
            "user-read-private",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-top-read",
            "user-read-playback-state",
            "user-modify-playback-state",
    };

    public static final String DEFAULT_MARKET = "US";

    /** The desktop client's scope contract used by Folia/librespot. Developer apps keep SCOPES. */
    private static final String BROWSER_SCOPES = String.join(" ",
            "app-remote-control", "playlist-modify", "playlist-modify-private", "playlist-modify-public", "playlist-read",
            "playlist-read-collaborative", "playlist-read-private", "streaming", "ugc-image-upload", "user-follow-modify",
            "user-follow-read", "user-library-modify", "user-library-read", "user-modify", "user-modify-playback-state",
            "user-modify-private", "user-personalized", "user-read-birthdate", "user-read-currently-playing", "user-read-email",
            "user-read-play-history", "user-read-playback-position", "user-read-playback-state", "user-read-private",
            "user-read-recently-played", "user-top-read");

    /** Spotify Client ID 是一串 32 位左右的 base62。长度放宽到 20 以上，只拦手滑截断。 */
    private static final Pattern CLIENT_ID_SHAPE = Pattern.compile("[0-9a-zA-Z]{20,}");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;
    private static boolean diskless;

    private SpotifyConfigStore() {
    }

    public static final class Config {
        public final String clientId;
        public final String market;
        public final LoginMode loginMode;

        Config(Data source) {
            this.clientId = nonBlank(source.clientId);
            String market = nonBlank(source.market).trim().toUpperCase(Locale.ROOT);
            this.market = market.isEmpty() ? DEFAULT_MARKET : market;
            // 旧配置没有 mode：已经配过 ID 的用户保留开发者登录，首次使用走浏览器登录。
            this.loginMode = source.loginMode == null
                    ? (clientId.isEmpty() ? LoginMode.BROWSER : LoginMode.DEVELOPER) : source.loginMode;
        }

        /** Client ID 形状对不对。缺配置与形状不对都算「未配置」，UI 据此给出不同的提示。 */
        public boolean clientIdLooksValid() {
            return CLIENT_ID_SHAPE.matcher(clientId).matches();
        }

        public boolean configured() {
            return loginMode == LoginMode.BROWSER || clientIdLooksValid();
        }

        public String authorizationClientId() {
            return loginMode == LoginMode.BROWSER ? BROWSER_CLIENT_ID : clientId;
        }

        public String redirectUri() {
            return loginMode == LoginMode.BROWSER ? BROWSER_REDIRECT_URI : REDIRECT_URI;
        }
    }

    public static Config snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new Config(data);
        }
    }

    public static String clientId() {
        return snapshot().authorizationClientId();
    }

    public static void saveLoginMode(LoginMode mode) {
        synchronized (LOCK) {
            ensureLoaded();
            data.loginMode = java.util.Objects.requireNonNull(mode);
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /** 拼好的 scope 串（授权 URL 与 token 交换共用同一份）。 */
    public static String scope() {
        return String.join(" ", SCOPES);
    }

    public static String scope(LoginMode mode) {
        return mode == LoginMode.BROWSER ? BROWSER_SCOPES : scope();
    }

    /**
     * 保存用户粘贴的 Client ID。形状不对返回 false —— 存一个必然被授权页拒绝的值，
     * 只会让用户在浏览器里看到一次看不懂的 {@code INVALID_CLIENT}。
     */
    public static boolean saveClientId(String rawInput) {
        String clientId = nonBlank(rawInput).trim();
        if (!CLIENT_ID_SHAPE.matcher(clientId).matches()) return false;
        synchronized (LOCK) {
            ensureLoaded();
            data.clientId = clientId;
            data.loginMode = LoginMode.DEVELOPER;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
        return true;
    }

    /** 市场代码只影响搜索的地域可见性（某些发行区域限定曲目）。空串回默认值。 */
    public static void saveMarket(String rawInput) {
        String market = nonBlank(rawInput).trim().toUpperCase(Locale.ROOT);
        synchronized (LOCK) {
            ensureLoaded();
            data.market = market;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    // ── 测试钩子：静测不许碰磁盘，用内存替身撑起同一套语义 ────────────────────

    static void putForTest(String clientId, String market) {
        synchronized (LOCK) {
            data = new Data();
            data.clientId = clientId == null ? "" : clientId;
            data.market = market == null ? "" : market;
            loaded = true;
            diskless = true;
        }
    }

    static void clearForTest() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            diskless = true;
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    private static void ensureLoaded() {
        if (loaded) return;
        data = new Data();
        try {
            if (ConfigPaths.SPOTIFY_CONFIG.isFile()) {
                String raw = new String(Files.readAllBytes(ConfigPaths.SPOTIFY_CONFIG.toPath()),
                        StandardCharsets.UTF_8);
                Data parsed = GSON.fromJson(raw, Data.class);
                if (parsed != null) data = parsed;
            }
        } catch (Throwable throwable) {
            // 配置读失败等价于没配置过：登录页会让用户重新粘一次，不该把游戏开崩。
            System.err.println("[Music/Spotify] 配置读取失败，按未配置处理: " + throwable);
        }
        loaded = true;
    }

    private static void persistLocked() {
        if (diskless) return;
        try {
            java.io.File file = ConfigPaths.SPOTIFY_CONFIG;
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            Files.write(file.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException throwable) {
            System.err.println("[Music/Spotify] 配置写入失败: " + throwable);
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Data {
        String clientId = "";
        String market = DEFAULT_MARKET;
        LoginMode loginMode;
        long updatedAt;
    }
}
