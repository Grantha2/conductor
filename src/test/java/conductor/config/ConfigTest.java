package conductor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void placeholdersAreNotRealKeysAndDefaultsApply(@TempDir Path dir) throws IOException {
        var file = dir.resolve("config.properties");
        Files.writeString(file, """
                anthropic.key=YOUR_ANTHROPIC_KEY_HERE
                openai.key=YOUR_OPENAI_KEY_HERE
                gemini.key=YOUR_GEMINI_KEY_HERE
                """);
        var c = new Config(file);

        assertFalse(c.hasAllKeys());
        assertEquals("claude-opus-5", c.anthropicModel());
        assertEquals("https://api.anthropic.com", c.anthropicUrl());
        assertEquals("gpt-5.4-mini", c.openaiModel());
        assertEquals("https://api.openai.com/v1/chat/completions", c.openaiUrl());
        assertEquals("gemini-3.1-pro-preview", c.geminiModel());
        assertEquals(16000, c.maxTokens());
        assertEquals(1, c.debateRounds());
        assertFalse(c.openclawEnabled());
        assertEquals("default", c.openclawAgentId());
        assertEquals("", c.openclawToken());
    }

    @Test
    void realKeysAndOverridesAreRead(@TempDir Path dir) throws IOException {
        var file = dir.resolve("config.properties");
        Files.writeString(file, """
                anthropic.key=sk-ant-real
                openai.key=sk-real
                gemini.key=AIzaReal
                anthropic.model=claude-sonnet-5
                max.tokens=4000
                debate.rounds=2
                openclaw.base.url=http://gw:18789
                openclaw.token=t0k
                openclaw.agent.id=research
                """);
        var c = new Config(file);
        assertTrue(c.hasAllKeys());
        assertEquals("claude-sonnet-5", c.anthropicModel());
        assertEquals(4000, c.maxTokens());
        assertEquals(2, c.debateRounds());
        assertTrue(c.openclawEnabled());
        assertEquals("http://gw:18789", c.openclawBaseUrl());
        assertEquals("t0k", c.openclawToken());
        assertEquals("research", c.openclawAgentId());
    }

    @Test
    void numericTyposFallBackToDefaultsInsteadOfCrashingStartup(@TempDir Path dir) throws IOException {
        var file = dir.resolve("config.properties");
        Files.writeString(file, "max.tokens=16k\ndebate.rounds=two\nopenclaw.base.url=   \n");
        var c = new Config(file);
        assertEquals(16000, c.maxTokens());
        assertEquals(1, c.debateRounds());
        assertFalse(c.openclawEnabled(), "whitespace-only base url means disabled");
    }

    @Test
    void missingFileExplainsWhatToDo(@TempDir Path dir) {
        var ex = assertThrows(IOException.class, () -> new Config(dir.resolve("nope.properties")));
        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("config.properties.example"));
        assertEquals(Path.of("config.properties"), Config.defaultPath());
    }
}
