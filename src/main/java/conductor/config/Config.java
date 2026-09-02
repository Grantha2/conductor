package conductor.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Typed view over {@code config.properties}: API keys, model names, endpoint
 * overrides and debate tuning. Keys never live in source; the file is
 * gitignored and copied from {@code config.properties.example}.
 */
public final class Config {

    private final Properties props = new Properties();

    public Config(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + " not found. Copy config.properties.example to config.properties "
                    + "and fill in your API keys.");
        }
        try (var in = Files.newBufferedReader(file)) {
            props.load(in);
        }
    }

    public static Path defaultPath() { return Path.of("config.properties"); }

    /** True when all three cloud providers have real (non-placeholder) keys. */
    public boolean hasAllKeys() {
        return isReal(anthropicKey()) && isReal(openaiKey()) && isReal(geminiKey());
    }

    public String anthropicKey()   { return get("anthropic.key", ""); }
    public String anthropicModel() { return get("anthropic.model", "claude-opus-5"); }
    public String anthropicUrl()   { return get("anthropic.url", "https://api.anthropic.com"); }

    public String openaiKey()   { return get("openai.key", ""); }
    public String openaiModel() { return get("openai.model", "gpt-5.4-mini"); }
    public String openaiUrl()   { return get("openai.url", "https://api.openai.com/v1/chat/completions"); }

    public String geminiKey()   { return get("gemini.key", ""); }
    public String geminiModel() { return get("gemini.model", "gemini-3.1-pro-preview"); }

    public boolean openclawEnabled() { return !openclawBaseUrl().isBlank(); }
    public String openclawBaseUrl()  { return get("openclaw.base.url", ""); }
    public String openclawToken()    { return get("openclaw.token", ""); }
    public String openclawAgentId()  { return get("openclaw.agent.id", "default"); }

    public int maxTokens()    { return getInt("max.tokens", 16000); }
    public int debateRounds() { return getInt("debate.rounds", 1); }

    private String get(String key, String fallback) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /** A typo in a numeric setting falls back to the default instead of crashing startup. */
    private int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isReal(String key) {
        return !key.isBlank() && !key.startsWith("YOUR_");
    }
}
