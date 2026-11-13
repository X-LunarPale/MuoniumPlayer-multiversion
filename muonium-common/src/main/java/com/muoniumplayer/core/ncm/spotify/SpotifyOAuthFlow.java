package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spotify 的 OAuth 2.0 + PKCE 授权流。
 *
 * <p>桌面端没有后端可以保管 client secret，所以走<b>授权码 + PKCE</b>（Spotify 官方为
 * 移动 / 桌面应用指定的那套）：本地起一个一次性 HTTP 服务接住浏览器跳回来的 {@code code}，
 * 用 {@code code_verifier} 去换 token。全程不出现 secret，落盘的只有 token 本身。</p>
 *
 * <p>流程分三步，UI 逐段给反馈：</p>
 * <ol>
 *   <li>{@link #beginLogin()} —— 生成 verifier/challenge/state，起回调服务，返回授权 URL；</li>
 *   <li>用户在浏览器里授权，Spotify 跳回所选登录模式对应的本地回调，服务校验
 *       state 后拿住 code；</li>
 *   <li>后台线程拿 code + verifier 换 token，落盘后再拉一次 {@code /me} 补账号资料，
 *       状态推进到 {@link #DONE}。UI 轮询 {@link #pollStatus()} 画进度。</li>
 * </ol>
 *
 * <p>回调服务是<b>一次性</b>的：收到 code（或用户点了拒绝、或 5 分钟没人回来）就关掉。
 * 它不该在用户整个游戏会话里一直监听一个端口。</p>
 */
public final class SpotifyOAuthFlow {

    /** 授权流当前所处的阶段；UI 按它画提示文案。 */
    public enum Status { IDLE, WAITING_BROWSER, EXCHANGING, DONE, FAILED }

    private static final long CALLBACK_TIMEOUT_MILLIS = 5 * 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    /** 本次授权流的 PKCE verifier；code 换 token 时必须带上同一条。 */
    private static String pendingVerifier = "";
    /** 防 CSRF：浏览器跳回来时 query 里的 state 必须与发出时一致。 */
    private static String pendingState = "";
    private static HttpServer callbackServer;
    private static java.util.concurrent.ExecutorService callbackExecutor;
    private static long generation;
    private static SpotifyConfigStore.Config pendingConfig;
    private static java.util.Timer watchdog;

    private static volatile Status status = Status.IDLE;
    private static volatile String statusMessage = "";
    private static final AtomicReference<Throwable> lastError = new AtomicReference<>();

    private SpotifyOAuthFlow() {
    }

    public static Status currentStatus() {
        return status;
    }

    public static String statusMessage() {
        return statusMessage;
    }

    /** 每次查询只消费一次的失败详情（UI 弹完就清）。 */
    public static Throwable consumeError() {
        return lastError.getAndSet(null);
    }

    /**
     * 开始一次授权。成功返回要在浏览器里打开的授权 URL；回调服务已经就位。
     *
     * @throws IOException 端口被占（多半是上一次流程卡着没退，或另一个游戏实例同时开授权）
     */
    public static String beginLogin() throws IOException {
        synchronized (LOCK) {
            stopServerLocked();
            long attempt = ++generation;
            pendingConfig = SpotifyConfigStore.snapshot();
            if (!pendingConfig.configured()) throw new IOException("请先配置 Spotify Client ID");
            final java.net.URI redirect = java.net.URI.create(pendingConfig.redirectUri());
            byte[] raw = new byte[64];
            RANDOM.nextBytes(raw);
            pendingVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            byte[] stateRaw = new byte[16];
            RANDOM.nextBytes(stateRaw);
            pendingState = Base64.getUrlEncoder().withoutPadding().encodeToString(stateRaw);

            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", redirect.getPort()), 0);
            callbackServer.createContext(redirect.getPath(), exchange -> handleCallback(exchange, attempt));
            // 守护线程：HTTP 处理器里的换 token 请求不该拖住游戏退出。
            callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Spotify OAuth Callback");
                thread.setDaemon(true);
                return thread;
            });
            callbackServer.setExecutor(callbackExecutor);
            callbackServer.start();
            scheduleWatchdog();

            status = Status.WAITING_BROWSER;
            lastError.set(null);
            statusMessage = "等待浏览器授权…";
            return buildAuthorizeUrl(pendingState, pendingVerifier);
        }
    }

    /** 用户中途关掉授权页后由 UI 调：把回调服务收掉，状态归零。 */
    public static void cancel() {
        synchronized (LOCK) {
            generation++;
            stopServerLocked();
            pendingVerifier = "";
            pendingState = "";
            status = Status.IDLE;
            statusMessage = "";
        }
    }

    // ── 纯函数：授权 URL 与 PKCE ─────────────────────────────────────────────

    /** 拼授权 URL。state/verifier 由调用方持有，换 token 时要原样带回去。 */
    static String buildAuthorizeUrl(String state, String codeVerifier) {
        return buildAuthorizeUrl(SpotifyConfigStore.snapshot(), state, codeVerifier);
    }

    private static String buildAuthorizeUrl(SpotifyConfigStore.Config config, String state, String codeVerifier) {
        StringBuilder url = new StringBuilder(SpotifyConfigStore.ACCOUNTS_BASE)
                .append(SpotifyConfigStore.AUTHORIZE_PATH)
                .append("?response_type=code")
                .append("&client_id=").append(urlEncode(config.authorizationClientId()))
                .append("&redirect_uri=").append(urlEncode(config.redirectUri()))
                .append("&state=").append(urlEncode(state))
                .append("&code_challenge_method=S256")
                .append("&code_challenge=").append(urlEncode(pkceChallenge(codeVerifier)))
                .append("&scope=").append(urlEncode(SpotifyConfigStore.scope(config.loginMode)));
        return url.toString();
    }

    /**
     * PKCE 的 S256 challenge：{@code BASE64URL(SHA256(ASCII(verifier)))}。
     *
     * <p>包成独立纯函数是为了拿 RFC 7636 附录 B 的官方向量做回归——challenge 算错时
     * token 端点只会回一句笼统的 {@code invalid_grant}，查起来没有方向。</p>
     */
    static String pkceChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Throwable throwable) {
            // JVM 不可能没有 SHA-256；真发生了，授权流报失败而不是发一个错误的 challenge。
            throw new IllegalStateException("PKCE challenge 不可用", throwable);
        }
    }

    /** 回调端口独立成常量供诊断文案引用；与 {@link SpotifyConfigStore#REDIRECT_URI} 必须一致。 */
    public static int callbackPort() {
        return java.net.URI.create(SpotifyConfigStore.snapshot().redirectUri()).getPort();
    }

    // ── 回调服务 ─────────────────────────────────────────────────────────────

    private static void handleCallback(HttpExchange exchange, long attempt) {
        try {
            final SpotifyConfigStore.Config config;
            final String verifier;
            synchronized (LOCK) {
                if (generation != attempt || pendingConfig == null) {
                    respond(exchange, 400, "此授权已失效，请回到游戏重新发起。");
                    return;
                }
                config = pendingConfig;
                verifier = pendingVerifier;
            }
            if (!"GET".equals(exchange.getRequestMethod()) || !exchange.getRequestURI().getPath()
                    .equals(java.net.URI.create(config.redirectUri()).getPath())) {
                respond(exchange, 404, "未找到此页面。");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String state = query.get("state");
            // 一次性消费：state 对不上（CSRF 或重放的旧链接）当作完全没见过这次回调。
            boolean stateOk;
            synchronized (LOCK) {
                stateOk = generation == attempt && !pendingState.isEmpty() && pendingState.equals(state);
                if (stateOk) {
                    pendingState = "";
                    stopWatchdogLocked();
                }
            }
            if (!stateOk) {
                respond(exchange, 400, "授权回调校验失败（state 不匹配），请回到游戏中重新发起授权。");
                return;
            }

            String error = query.get("error");
            if (error != null && !error.trim().isEmpty()) {
                respond(exchange, 200, "授权未完成。可以回到游戏中重新发起。");
                synchronized (LOCK) {
                    if (generation == attempt) finishFailed("授权被取消或拒绝");
                }
                return;
            }
            String code = query.get("code");
            if (code == null || code.trim().isEmpty()) {
                respond(exchange, 400, "回调缺少 code 参数，请回到游戏中重新发起授权。");
                synchronized (LOCK) {
                    if (generation == attempt) finishFailed("回调里没有授权码");
                }
                return;
            }

            respond(exchange, 200, "已收到授权，正在完成登录。请回到游戏查看结果。");
            // verifier 在提交前先取快照：执行器异步跑起来之后，cancel() 可能已经把它清掉。
            final String authorizationCode = code.trim();
            synchronized (LOCK) {
                if (generation != attempt) return;
                status = Status.EXCHANGING;
                statusMessage = "正在换取 token…";
            }
            // 交给回调服务的守护执行器跑：这一步是两次网络往返（换 token + 拉 /me）。
            Thread worker = new Thread(() -> exchangeCode(authorizationCode, verifier, config, attempt),
                    "Spotify OAuth Exchange");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable throwable) {
            // 恶意/损坏的 query 不能消费或终止有效登录。
            try { respond(exchange, 400, "授权回调格式错误，请重新尝试。"); }
            catch (IOException ignored) { }
        } finally {
            exchange.close();
        }
    }

    /**
     * 用授权码换 token 并完成登录（落盘凭据 + 拉账号资料）。
     *
     * <p>单独成方法（不起服务、不发浏览器）让静测可以直接对 token 端点的请求形状做断言。</p>
     */
    static void exchangeCode(String code, String codeVerifier) {
        exchangeCode(code, codeVerifier, SpotifyConfigStore.snapshot(), generation);
    }

    private static void exchangeCode(String code, String codeVerifier,
                                     SpotifyConfigStore.Config config, long attempt) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", config.redirectUri());
            form.put("client_id", config.authorizationClientId());
            form.put("code_verifier", codeVerifier);
            JsonObject payload = SpotifyWebApiClient.postForm(
                    SpotifyConfigStore.ACCOUNTS_BASE + SpotifyConfigStore.TOKEN_PATH, form);
            synchronized (LOCK) {
                if (generation != attempt) return;
                SpotifyAuthStore.saveAuthorization(payload, config.authorizationClientId());
            }
            fetchProfile(attempt);
            synchronized (LOCK) {
                if (generation != attempt) return;
                status = Status.DONE;
                statusMessage = "已登录：" + SpotifyAuthStore.snapshot().accountName;
            }
        } catch (Throwable throwable) {
            synchronized (LOCK) {
                if (generation != attempt) return;
                lastError.set(throwable);
                finishFailed("Spotify 登录失败，请检查网络或切换登录方式");
            }
        } finally {
            synchronized (LOCK) {
                if (generation == attempt) {
                    stopServerLocked();
                    pendingVerifier = "";
                }
            }
        }
    }

    /** 换到 token 后拉一次 {@code /me}；失败不影响登录状态本身。 */
    private static void fetchProfile(long attempt) {
        try {
            JsonObject me = SpotifyDesktopCatalog.usesDesktopSession()
                    ? SpotifyDesktopCatalog.profile() : SpotifyWebApiClient.getJson("/me", null);
            String name = me.has("display_name") && !me.get("display_name").isJsonNull()
                    ? me.get("display_name").getAsString() : "";
            String id = me.has("id") && !me.get("id").isJsonNull() ? me.get("id").getAsString() : "";
            String avatar = "";
            if (me.has("images") && me.get("images").isJsonArray() && me.getAsJsonArray("images").size() > 0) {
                com.google.gson.JsonElement first = me.getAsJsonArray("images").get(0);
                if (first != null && first.isJsonObject() && first.getAsJsonObject().has("url")
                        && !first.getAsJsonObject().get("url").isJsonNull()) {
                    avatar = first.getAsJsonObject().get("url").getAsString();
                }
            }
            synchronized (LOCK) {
                if (generation == attempt) SpotifyAuthStore.updateProfile(name, id, avatar);
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/Spotify] 账号资料拉取失败（不影响登录）: " + throwable);
        }
    }

    private static void finishFailed(String message) {
        status = Status.FAILED;
        statusMessage = message;
        synchronized (LOCK) {
            stopServerLocked();
        }
    }

    private static void respond(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = ("<html><body style=\"font-family:sans-serif;background:#121212;color:#fff;"
                + "display:grid;place-items:center;height:100vh\"><p style=\"max-width:32em;text-align:center\">"
                + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void scheduleWatchdog() {
        stopWatchdogLocked();
        watchdog = new java.util.Timer("Spotify OAuth Watchdog", true);
        watchdog.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    if (status == Status.WAITING_BROWSER) {
                        stopServerLocked();
                        status = Status.FAILED;
                        statusMessage = "授权超时：五分钟内没有等到浏览器跳回，请重新发起";
                    }
                }
            }
        }, CALLBACK_TIMEOUT_MILLIS);
    }

    private static void stopWatchdogLocked() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private static void stopServerLocked() {
        stopWatchdogLocked();
        if (callbackServer != null) {
            try {
                callbackServer.stop(0);
            } catch (Throwable ignored) {
            }
            callbackServer = null;
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdown();
            callbackExecutor = null;
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int assign = pair.indexOf('=');
            if (assign <= 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, assign), StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(pair.substring(assign + 1), StandardCharsets.UTF_8.name());
                if (out.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复回调参数");
            } catch (UnsupportedEncodingException impossible) {
                return out;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }
    }
}
