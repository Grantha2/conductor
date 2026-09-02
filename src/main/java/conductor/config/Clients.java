package conductor.config;

import conductor.agents.AgentClient;
import conductor.agents.AnthropicClient;
import conductor.agents.GeminiClient;
import conductor.agents.OpenAiClient;
import conductor.agents.OpenClawClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires {@link Config} into the panel's client list. Order matters: index 0
 * (Anthropic) is the lead that writes the synthesis. OpenClaw is appended only
 * when a gateway base URL is configured.
 */
public final class Clients {

    private Clients() {}

    public static List<AgentClient> build(Config c, HttpClient http) {
        var clients = new ArrayList<AgentClient>();
        clients.add(new AnthropicClient(http, c.anthropicUrl(), c.anthropicKey(), c.anthropicModel()));
        clients.add(new OpenAiClient(http, c.openaiUrl(), c.openaiKey(), c.openaiModel()));
        clients.add(new GeminiClient(http, c.geminiKey(), c.geminiModel()));
        if (c.openclawEnabled()) {
            clients.add(new OpenClawClient(http, c.openclawBaseUrl(), c.openclawToken(), c.openclawAgentId()));
        }
        return List.copyOf(clients);
    }
}
