package com.muoniumplayer.core.ncm.spotify;

import com.sun.jna.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpotifySoloistStoreTest {
    private static final String KEY = "test-soloist-key-never-a-real-credential";
    @TempDir Path directory;
    private Path config;
    private Path program;

    @BeforeEach void prepare() throws IOException {
        config = directory.resolve("auth/soloist.json");
        Path unpacked = Files.createDirectories(directory.resolve("soloist release"));
        program = Files.createFile(unpacked.resolve(Platform.isWindows() ? "soloist.exe" : "soloist"));
        SpotifySoloistStore.resetForTest(config);
    }

    @AfterEach void reset() { SpotifySoloistStore.resetForTest(null); }

    @Test void unpackedDirectoryResolvesItsOfficialProgram() throws Exception {
        SpotifySoloistStore.save(program.getParent().toString(), KEY);
        assertEquals(program.toAbsolutePath().toString(), SpotifySoloistStore.executable());
        assertEquals(KEY, SpotifySoloistStore.key());
    }

    @Test void quotedProgramPathAndBlankKeyKeepExistingCredential() throws Exception {
        SpotifySoloistStore.save("\"" + program + "\"", KEY);
        SpotifySoloistStore.save(program.toString(), " ");
        assertEquals(KEY, SpotifySoloistStore.key());
        assertFalse(Files.readString(config).contains(KEY), "配置不得写入明文 Key");
    }

    @Test void invalidDirectoryDoesNotReplaceSavedCredential() throws Exception {
        SpotifySoloistStore.save(program.toString(), KEY);
        String before = Files.readString(config);
        IOException error = assertThrows(IOException.class,
                () -> SpotifySoloistStore.save(directory.toString(), "replacement-test-key"));
        assertTrue(error.getMessage().contains("soloist"), "路径错误应说明缺少哪个程序");
        assertEquals(before, Files.readString(config));
        assertEquals(KEY, SpotifySoloistStore.key());
    }

    @Test void keySurvivesWindowsConfigReload() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Platform.isWindows());
        SpotifySoloistStore.save(program.toString(), KEY);
        SpotifySoloistStore.resetForTest(config);
        assertTrue(SpotifySoloistStore.hasKey());
        assertEquals(KEY, SpotifySoloistStore.key());
    }

    @Test void savedSetupReconnectsAfterRestartWithoutAnotherKey() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Platform.isWindows());
        SpotifySoloistStore.save(program.toString(), KEY);
        SpotifySoloistStore.resetForTest(config);
        assertTrue(SpotifySoloistStore.reconnectOnStartup());
        assertEquals(KEY, SpotifySoloistStore.key());
    }

    @Test void existingSetupWithoutReconnectFieldStillRestores() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Platform.isWindows());
        SpotifySoloistStore.save(program.toString(), KEY);
        var json = com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        json.remove("reconnectOnStartup");
        Files.writeString(config, json.toString());
        SpotifySoloistStore.resetForTest(config);
        assertTrue(SpotifySoloistStore.reconnectOnStartup(), "旧配置中的 Key 和程序应直接沿用");
    }

    @Test void explicitDisconnectStaysDisabledAfterRestartAndNextStartReenablesIt() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Platform.isWindows());
        SpotifySoloistStore.save(program.toString(), KEY);
        SpotifySoloistStore.disableReconnect();
        SpotifySoloistStore.resetForTest(config);
        assertFalse(SpotifySoloistStore.reconnectOnStartup());
        assertEquals(KEY, SpotifySoloistStore.key(), "断开不应丢掉 Key 或配对资料");
        SpotifySoloistStore.save(program.toString(), "");
        SpotifySoloistStore.resetForTest(config);
        assertTrue(SpotifySoloistStore.reconnectOnStartup());
    }

    @Test void missingSetupNeverStartsAutomatically() {
        assertFalse(SpotifySoloistStore.reconnectOnStartup());
    }
}
