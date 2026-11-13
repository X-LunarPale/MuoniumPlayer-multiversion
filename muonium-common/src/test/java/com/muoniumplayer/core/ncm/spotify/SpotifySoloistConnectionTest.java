package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** A local RFC 6455 peer exercises the actual JDK WebSocket listener and command path. */
class SpotifySoloistConnectionTest {
    @TempDir Path data;

    @Test
    void liveLeftoverEndpointIsPreservedAndPreventsAnotherProcess() throws Exception {
        try (ServerSocket active = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String port = Integer.toString(active.getLocalPort());
            Files.writeString(data.resolve("ws.port"), port);
            Files.writeString(data.resolve("ws.addr"), "127.0.0.1:" + port);
            IOException error = assertThrows(IOException.class, () -> SpotifySoloistService.checkEndpointInactive(data));
            assertTrue(error.getMessage().contains("Soloist"));
            assertEquals(port, Files.readString(data.resolve("ws.port")));
            assertEquals("127.0.0.1:" + port, Files.readString(data.resolve("ws.addr")));
        }
    }

    @Test
    void deadEndpointDoesNotBlockStartup() throws Exception {
        int port;
        try (ServerSocket dead = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = dead.getLocalPort();
        }
        Files.writeString(data.resolve("ws.port"), Integer.toString(port));
        Files.writeString(data.resolve("ws.addr"), "127.0.0.1:" + port);
        assertDoesNotThrow(() -> SpotifySoloistService.checkEndpointInactive(data));
    }

    @Test
    void incompleteEndpointFromFailedStartupDoesNotBlockStartup() throws Exception {
        Files.writeString(data.resolve("ws.port"), "0");
        assertDoesNotThrow(() -> SpotifySoloistService.checkEndpointInactive(data));
    }

    @AfterEach
    void disconnect() {
        SpotifyConnectMirror.setMainThreadForTest(task -> { });
        SpotifyConnectMirror.stop(null);
        SpotifyConnectMirror.setMainThreadForTest(null);
        SpotifySoloistService.disconnect();
        com.muoniumplayer.core.ncm.music.CloudMusic.playList = new java.util.ArrayList<>();
        com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying = null;
        com.muoniumplayer.core.ncm.music.CloudMusic.curIdx = 0;
        SpotifyWebApiClient.resetTransportForTest();
        SpotifyAuthStore.clearForTest();
    }

    @Test
    void playingPlaylistRetainsWholeGameQueueAndAddNextDoesNotPlayImmediately() throws Exception {
        SpotifyConnectMirror.setMainThreadForTest(task -> { });
        var first = song("2JRo0gjbX4GrCqBYdRohoo");
        var second = song("7qiZfU4dY1lWllzX7mPBI3");
        var inserted = song("56UzFYxgkvxU188BsCKBOc");
        var originals = new java.util.ArrayList<>(java.util.List.of(first, second));
        com.muoniumplayer.core.ncm.music.CloudMusic.playList = new java.util.ArrayList<>();
        com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying = null;
        com.muoniumplayer.core.ncm.music.CloudMusic.playMode = com.muoniumplayer.core.ncm.music.CloudMusic.PlayMode.Sequential;
        try (Peer peer = paired()) {
            peer.autoPlayback = true;
            com.muoniumplayer.core.ncm.music.CloudMusic.play(originals, 0);
            assertEquals(originals, com.muoniumplayer.core.ncm.music.CloudMusic.playList);
            assertEquals(0, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
            assertTrue(com.muoniumplayer.core.ncm.music.CloudMusic.playNext(inserted));
            assertEquals(3, com.muoniumplayer.core.ncm.music.CloudMusic.playList.size());
            assertEquals(inserted, com.muoniumplayer.core.ncm.music.CloudMusic.playList.get(1));
            assertEquals(1, com.muoniumplayer.core.ncm.music.CloudMusic.getQueuedNextCount());
            assertEquals(2, originals.size(), "Adding to queue must not mutate the source playlist");
            JsonObject play = peer.command();
            while (!play.get("command").getAsString().equals("play")) play = peer.command();
            assertEquals("spotify:track:" + first.getSourceId(), play.get("uri").getAsString());
            assertNull(peer.commands.poll(150, TimeUnit.MILLISECONDS), "Adding next must not issue another play");
            com.muoniumplayer.core.ncm.music.CloudMusic.next();
            JsonObject next = peer.command();
            while (!next.get("command").getAsString().equals("play")) next = peer.command();
            assertEquals("spotify:track:" + inserted.getSourceId(), next.get("uri").getAsString());
            assertEquals(1, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
            com.muoniumplayer.core.ncm.music.CloudMusic.next();
            JsonObject after = peer.command();
            while (!after.get("command").getAsString().equals("play")) after = peer.command();
            assertEquals("spotify:track:" + second.getSourceId(), after.get("uri").getAsString());
        } finally {
            com.muoniumplayer.core.ncm.music.CloudMusic.playList = new java.util.ArrayList<>();
            com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying = null;
            com.muoniumplayer.core.ncm.music.CloudMusic.curIdx = 0;
        }
    }

    private static com.muoniumplayer.core.ncm.music.dto.Music song(String id) {
        SpotifyMusicService.Track track = new SpotifyMusicService.Track();
        track.id = id; track.name = "Track " + id; track.durationMillis = 100000;
        return com.muoniumplayer.core.ncm.music.dto.Music.fromSpotifyTrack(track);
    }

    @Test
    void naturalEndPausesRecommendationAndAdvancesGamePlaylistOnlyOnce() throws Exception {
        var tasks = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        SpotifyConnectMirror.setMainThreadForTest(tasks::add);
        var first = song("2JRo0gjbX4GrCqBYdRohoo");
        var second = song("7qiZfU4dY1lWllzX7mPBI3");
        com.muoniumplayer.core.ncm.music.CloudMusic.playMode = com.muoniumplayer.core.ncm.music.CloudMusic.PlayMode.Sequential;
        try (Peer peer = paired()) {
            peer.autoPlayback = true;
            com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(first, second), 0);
            assertEquals("spotify:track:" + first.getSourceId(), peer.untilCommand("play").get("uri").getAsString());
            await(() -> tasks.size() >= 2); // startup + initial adoption; no GUI/lyrics are run in this protocol test
            tasks.clear();
            peer.send("{\"type\":\"position_sync\",\"position\":{\"position_ms\":99000}}");
            peer.send("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:56UzFYxgkvxU188BsCKBOc\"}}");
            peer.untilCommand("pause");
            await(() -> !tasks.isEmpty());
            tasks.remove().run();
            assertEquals(1, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
            assertEquals("spotify:track:" + second.getSourceId(), peer.untilCommand("play").get("uri").getAsString());
            assertEquals(2, com.muoniumplayer.core.ncm.music.CloudMusic.playList.size());
            await(() -> !tasks.isEmpty());
            tasks.clear();
            peer.send("{\"type\":\"position_sync\",\"position\":{\"position_ms\":99000}}");
            peer.send("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:56UzFYxgkvxU188BsCKBOc\"}}");
            peer.untilCommand("pause");
            await(() -> !tasks.isEmpty());
            tasks.remove().run();
            assertEquals(1, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
            assertFalse(SpotifyConnectMirror.isPlaying());
            assertNull(peer.commands.poll(300, TimeUnit.MILLISECONDS), "Sequential queue must stop at its end");
        }
    }

    @Test
    void lateEndCallbackCannotAdvanceANewerPlaylistSelection() throws Exception {
        var tasks = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        SpotifyConnectMirror.setMainThreadForTest(tasks::add);
        var first = song("2JRo0gjbX4GrCqBYdRohoo");
        var second = song("7qiZfU4dY1lWllzX7mPBI3");
        var replacement = song("56UzFYxgkvxU188BsCKBOc");
        com.muoniumplayer.core.ncm.music.CloudMusic.playMode = com.muoniumplayer.core.ncm.music.CloudMusic.PlayMode.Sequential;
        try (Peer peer = paired()) {
            peer.autoPlayback = true;
            com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(first, second), 0);
            peer.untilCommand("play");
            await(() -> tasks.size() >= 2);
            tasks.clear();
            peer.send("{\"type\":\"position_sync\",\"position\":{\"position_ms\":99000}}");
            peer.send("{\"type\":\"track_changed\",\"item\":null}");
            peer.untilCommand("pause");
            await(() -> !tasks.isEmpty());
            Runnable oldEnd = tasks.remove();
            com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(replacement, first), 0);
            oldEnd.run();
            assertEquals(0, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
            assertEquals(replacement, com.muoniumplayer.core.ncm.music.CloudMusic.playList.get(0));
            assertEquals("spotify:track:" + replacement.getSourceId(), peer.untilCommand("play").get("uri").getAsString());
            assertNull(peer.commands.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void lateFailureOrReplacementCannotRetireANewerPlaylistSelection() throws Exception {
        SpotifyConnectMirror.setMainThreadForTest(task -> { });
        var first = song("2JRo0gjbX4GrCqBYdRohoo");
        var replacement = song("56UzFYxgkvxU188BsCKBOc");
        var managed = SpotifyConnectMirror.class.getDeclaredField("managedTrack");
        var generation = SpotifyConnectMirror.class.getDeclaredField("mirrorGeneration");
        var retire = SpotifyConnectMirror.class.getDeclaredMethod("retireManagedSelection",
                SpotifySoloistTrackWatch.class, long.class, SpotifySoloistTrackWatch.Outcome.class);
        managed.setAccessible(true);
        generation.setAccessible(true);
        retire.setAccessible(true);
        try (Peer peer = paired()) {
            peer.autoPlayback = true;
            for (var terminal : java.util.List.of(SpotifySoloistTrackWatch.Outcome.FAILED,
                    SpotifySoloistTrackWatch.Outcome.REPLACED)) {
                com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(first), 0);
                peer.untilCommand("play");
                Object old = managed.get(null);
                long attempt = generation.getLong(null);
                com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(replacement, first), 0);
                Object current = managed.get(null);
                assertNotSame(old, current);
                assertEquals(false, retire.invoke(null, old, attempt, terminal));
                assertSame(current, managed.get(null));
                assertTrue(SpotifyConnectMirror.managesSoloistQueue());
                assertEquals(2, com.muoniumplayer.core.ncm.music.CloudMusic.playList.size());
                assertEquals("spotify:track:" + replacement.getSourceId(), peer.untilCommand("play").get("uri").getAsString());
            }
        }
    }

    @Test
    void fragmentedPairingAndPlaybackArriveWithoutOAuthOrWebApi() throws Exception {
        SpotifyAuthStore.clearForTest();
        SpotifyWebApiClient.setTransportForTest(request -> { throw new AssertionError("Soloist must not use Web API"); });
        try (Peer peer = new Peer()) {
            SpotifySoloistService.connectForTest(peer.port());
            peer.frame(1, false, "{\"type\":\"auth_state\",");
            peer.frame(0, true, "\"logged_in\":true}");
            await(SpotifySoloistService::isPaired);
            peer.send("{\"type\":\"playback_state\",\"status\":\"paused\",\"volume\":30,"
                    + "\"position\":{\"position_ms\":10000},\"item\":{\"uri\":\"spotify:track:2JRo0gjbX4GrCqBYdRohoo\","
                    + "\"decorations\":{\"identity\":{\"name\":\"Test Song\"},\"playback\":{\"duration_ms\":210000}}}}");
            await(() -> SpotifySoloistService.playback() != null && SpotifySoloistService.playback().track != null);
            SpotifyConnectService.PlaybackState state = SpotifyConnectService.fetchPlayback();
            assertEquals("Test Song", state.track.name);
            assertEquals(10000, state.progressMs);
            assertFalse(state.playing);
            assertTrue(SpotifyMusicService.isLoggedIn());
            assertFalse(SpotifyAuthStore.isLoggedIn());
            assertEquals("soloist", SpotifyConnectService.fetchDevices().get(0).id);
        }
    }

    @Test
    void deviceControlsUseOnlyTheLocalProtocol() throws Exception {
        SpotifyAuthStore.clearForTest();
        SpotifyWebApiClient.setTransportForTest(request -> { throw new AssertionError("Soloist controls must stay local"); });
        try (Peer peer = paired()) {
            SpotifyConnectService.resume("soloist");
            assertEquals("play", peer.command().get("command").getAsString());
            SpotifyConnectService.next("soloist");
            assertEquals("skip_next", peer.command().get("command").getAsString());
            SpotifyConnectService.previous("soloist");
            assertEquals("skip_prev", peer.command().get("command").getAsString());
            SpotifyConnectService.seek(-100, "soloist");
            assertEquals(0, peer.command().get("position_ms").getAsInt());
            SpotifyConnectService.setVolume(200, "soloist");
            assertEquals(100, peer.command().get("volume").getAsInt());
            SpotifyConnectService.pause("");
            assertEquals("pause", peer.command().get("command").getAsString());
            SpotifyConnectService.resume("");
            assertEquals("play", peer.command().get("command").getAsString());
            SpotifyConnectService.next("");
            assertEquals("skip_next", peer.command().get("command").getAsString());
            SpotifyConnectService.previous("");
            assertEquals("skip_prev", peer.command().get("command").getAsString());
            SpotifyConnectService.setVolume(45, "");
            assertEquals(45, peer.command().get("volume").getAsInt());
            SpotifySoloistService.playTrack("2JRo0gjbX4GrCqBYdRohoo");
            JsonObject play = peer.command();
            assertEquals("play", play.get("command").getAsString());
            assertEquals("spotify:track:2JRo0gjbX4GrCqBYdRohoo", play.get("uri").getAsString());
            assertThrows(IllegalArgumentException.class, () -> SpotifySoloistService.playTrack("not-a-track"));
        }
    }

    @Test
    void episodePlaybackPreservesItsPlayableUri() throws Exception {
        try (Peer peer = paired()) {
            SpotifySoloistService.playTrack("episode:021rXuVPJNfwYDG4EgEJlk");
            assertEquals("spotify:episode:021rXuVPJNfwYDG4EgEJlk", peer.command().get("uri").getAsString());
        }
    }

    @Test
    void episodeInGamePlaylistUsesItsOriginalUriThenContinuesToTheNextSong() throws Exception {
        SpotifyConnectMirror.setMainThreadForTest(task -> { });
        var episode = song("episode:021rXuVPJNfwYDG4EgEJlk");
        var next = song("2JRo0gjbX4GrCqBYdRohoo");
        com.muoniumplayer.core.ncm.music.CloudMusic.playMode = com.muoniumplayer.core.ncm.music.CloudMusic.PlayMode.Sequential;
        try (Peer peer = paired()) {
            peer.autoPlayback = true;
            com.muoniumplayer.core.ncm.music.CloudMusic.play(java.util.List.of(episode, next), 0);
            assertEquals("spotify:episode:021rXuVPJNfwYDG4EgEJlk", peer.untilCommand("play").get("uri").getAsString());
            await(() -> SpotifySoloistService.playback().track != null
                    && episode.getSourceId().equals(SpotifySoloistService.playback().track.id));
            assertEquals(2, com.muoniumplayer.core.ncm.music.CloudMusic.playList.size());
            com.muoniumplayer.core.ncm.music.CloudMusic.next();
            assertEquals("spotify:track:" + next.getSourceId(), peer.untilCommand("play").get("uri").getAsString());
            assertEquals(1, com.muoniumplayer.core.ncm.music.CloudMusic.curIdx);
        }
    }

    @Test
    void disconnectDropsPairingAndRejectsCommands() throws Exception {
        try (Peer peer = paired()) {
            assertTrue(SpotifySoloistService.isRunning());
            SpotifySoloistService.disconnect();
            assertFalse(SpotifySoloistService.isRunning());
            assertFalse(SpotifySoloistService.isPaired());
            assertNull(SpotifySoloistService.playback());
            assertThrows(IOException.class, () -> SpotifySoloistService.send("play"));
        }
    }

    @Test
    void startingSavedProgramAgainKeepsThePairedConnection() throws Exception {
        Path config = data.resolve("soloist.json");
        Path program = Files.createFile(data.resolve("soloist.exe"));
        SpotifySoloistStore.resetForTest(config);
        try {
            SpotifySoloistStore.save(program.toString(), "test-key-for-reconnect");
            try (Peer peer = paired()) {
                SpotifySoloistService.start(program.toAbsolutePath().toString(), "");
                assertTrue(SpotifySoloistService.isPaired(), "沿用同一程序与已保存 Key 时不能拆掉已配对连接");
                SpotifySoloistService.playTrack("2JRo0gjbX4GrCqBYdRohoo");
                assertEquals("play", peer.untilCommand("play").get("command").getAsString());
            }
        } finally {
            SpotifySoloistService.disconnect();
            SpotifySoloistStore.resetForTest(null);
        }
    }

    @Test
    void automaticRestoreKeepsALiveSessionAndExplicitDisconnectDisablesTheNextRestore() throws Exception {
        Path config = data.resolve("reconnect.json");
        Path program = Files.createFile(data.resolve("soloist.exe"));
        SpotifySoloistStore.resetForTest(config);
        try {
            SpotifySoloistStore.save(program.toString(), "test-key-for-reconnect");
            try (Peer peer = paired()) {
                SpotifySoloistService.restoreSavedSession();
                drainLifecycleWorker();
                assertTrue(SpotifySoloistService.isPaired());
                SpotifySoloistService.playTrack("2JRo0gjbX4GrCqBYdRohoo");
                assertEquals("play", peer.untilCommand("play").get("command").getAsString());
                SpotifySoloistService.disconnectAndDisableReconnect();
                drainLifecycleWorker();
                SpotifySoloistService.restoreSavedSession();
                drainLifecycleWorker();
                assertFalse(SpotifySoloistService.isRunning());
                assertFalse(SpotifySoloistService.isStarting());
                assertFalse(SpotifySoloistStore.reconnectOnStartup());
                assertTrue(SpotifySoloistStore.hasKey());
            }
        } finally {
            SpotifySoloistService.disconnect();
            SpotifySoloistStore.resetForTest(null);
        }
    }

    private static void drainLifecycleWorker() throws Exception {
        for (String name : new String[]{"WORKER", "PREFERENCES"}) {
            var field = SpotifySoloistService.class.getDeclaredField(name);
            field.setAccessible(true);
            ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> { }).get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void explicitDisconnectPersistsEvenWhileStartupWorkerIsBlocked() throws Exception {
        Path config = data.resolve("blocked-reconnect.json");
        Path program = Files.createFile(data.resolve("soloist.exe"));
        SpotifySoloistStore.resetForTest(config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var field = SpotifySoloistService.class.getDeclaredField("WORKER");
        field.setAccessible(true);
        var worker = (java.util.concurrent.ExecutorService) field.get(null);
        try {
            SpotifySoloistStore.save(program.toString(), "test-key-for-reconnect");
            worker.submit(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            SpotifySoloistService.disconnectAndDisableReconnect();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (SpotifySoloistStore.reconnectOnStartup() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(SpotifySoloistStore.reconnectOnStartup(), "退出前保存断开设置不能排在耗时启动任务后面");
            SpotifySoloistStore.resetForTest(config);
            assertFalse(SpotifySoloistStore.reconnectOnStartup(), "独立读取磁盘时也必须已关闭重连");
        } finally {
            release.countDown();
            drainLifecycleWorker();
            SpotifySoloistStore.resetForTest(null);
        }
    }

    @Test
    void pairedDesktopAccountUsesLocalDeviceBeforeMirrorStarts() throws Exception {
        SpotifyAuthStore.putForTest("access", "refresh", System.currentTimeMillis() + 3600000);
        SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"access\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
        SpotifyWebApiClient.setTransportForTest(request -> { throw new AssertionError("Desktop Soloist device must stay local"); });
        try (Peer peer = paired()) {
            assertEquals("soloist", SpotifyConnectService.fetchDevices().get(0).id);
            assertNotNull(SpotifyConnectService.fetchPlayback());
            SpotifyConnectService.pause("");
            assertEquals("pause", peer.command().get("command").getAsString());
        }
    }

    @Test
    void peerClosureClearsPairingAndNewConnectionDoesNotInheritIt() throws Exception {
        try (Peer first = paired()) {
            first.closeConnection();
            await(() -> !SpotifySoloistService.isRunning());
            assertFalse(SpotifySoloistService.isPaired());
            try (Peer second = new Peer()) {
                SpotifySoloistService.connectForTest(second.port());
                assertFalse(SpotifySoloistService.isPaired());
                second.send("{\"type\":\"auth_state\",\"logged_in\":true}");
                await(SpotifySoloistService::isPaired);
            }
        }
    }

    @Test
    void malformedEventDoesNotPreventLaterValidState() throws Exception {
        try (Peer peer = paired()) {
            peer.send("{broken");
            peer.send("{\"type\":\"auth_state\",\"logged_in\":false}");
            await(() -> !SpotifySoloistService.isPaired());
            assertTrue(SpotifySoloistService.isRunning());
        }
    }

    private static Peer paired() throws Exception {
        Peer peer = new Peer();
        SpotifySoloistService.connectForTest(peer.port());
        peer.send("{\"type\":\"auth_state\",\"logged_in\":true}");
        await(SpotifySoloistService::isPaired);
        return peer;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for local WebSocket state");
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        private volatile Socket socket;
        private volatile boolean autoPlayback;

        Peer() throws IOException {
            Thread thread = new Thread(this::run, "Test Soloist Peer");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }

        void run() {
            try {
                socket = server.accept();
                InputStream input = socket.getInputStream();
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                String headers;
                do {
                    int value = input.read();
                    if (value < 0) throw new EOFException();
                    request.write(value);
                    headers = request.toString(StandardCharsets.US_ASCII);
                } while (!headers.endsWith("\r\n\r\n"));
                String key = headers.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:"))
                        .findFirst().orElseThrow().split(":", 2)[1].trim();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                accepted.countDown();
                while (!socket.isClosed()) {
                    int opcode = input.read();
                    if (opcode < 0) break;
                    int flags = input.read();
                    int length = flags & 127;
                    if (length == 126) length = input.read() * 256 + input.read();
                    if (length == 127) throw new IOException("Test frame too large");
                    byte[] mask = (flags & 128) != 0 ? input.readNBytes(4) : null;
                    byte[] payload = input.readNBytes(length);
                    if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                    if ((opcode & 15) == 1) {
                        String text = new String(payload, StandardCharsets.UTF_8);
                        commands.add(text);
                        JsonObject command = JsonParser.parseString(text).getAsJsonObject();
                        if (autoPlayback && "play".equals(command.get("command").getAsString()) && command.has("uri")) {
                            send("{\"type\":\"playback_state\",\"status\":\"playing\",\"item\":{\"uri\":"
                                    + command.get("uri") + ",\"duration_ms\":100000},\"position\":{\"position_ms\":0}}");
                        }
                    }
                    if ((opcode & 15) == 8) break;
                }
            } catch (Exception closed) {
                // Closing a peer intentionally terminates a blocked read/accept.
            } finally {
                accepted.countDown();
            }
        }

        void send(String value) throws Exception { frame(1, true, value); }

        synchronized void frame(int opcode, boolean last, String value) throws Exception {
            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            byte[] payload = value.getBytes(StandardCharsets.UTF_8);
            var out = socket.getOutputStream();
            out.write((last ? 128 : 0) | opcode);
            if (payload.length < 126) out.write(payload.length);
            else { out.write(126); out.write(payload.length >> 8); out.write(payload.length & 255); }
            out.write(payload);
            out.flush();
        }

        JsonObject command() throws Exception {
            String text = commands.poll(3, TimeUnit.SECONDS);
            assertNotNull(text, "No Soloist command received");
            return JsonParser.parseString(text).getAsJsonObject();
        }

        JsonObject untilCommand(String name) throws Exception {
            JsonObject command;
            do { command = command(); } while (!name.equals(command.get("command").getAsString()));
            return command;
        }

        void closeConnection() throws IOException { if (socket != null) socket.close(); }
        @Override public void close() throws IOException { closeConnection(); server.close(); }
    }
}
