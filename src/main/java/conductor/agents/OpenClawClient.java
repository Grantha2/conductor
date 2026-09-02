package conductor.agents;

import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.util.List;

/**
 * OpenClaw gateway client. OpenClaw exposes an OpenAI-compatible endpoint
 * ({@code POST {baseUrl}/v1/chat/completions}, Bearer token) where the
 * {@code model} field is an agent route: {@code "openclaw/<agentId>"}.
 *
 * <p>Tool calling and JSON-schema output are optional gateway features. We
 * send them in the OpenAI shape; if the gateway answers HTTP 400 to a request
 * that carried tools or a schema, we retry ONCE as plain text and prepend a
 * note to the reply so the caller knows structure was dropped. The OpenClaw
 * endpoint must be enabled in the gateway's own config for any of this to work.
 */
public final class OpenClawClient implements AgentClient {

    public static final String DEFAULT_BASE_URL = "http://localhost:18789";
    static final String DEGRADED_NOTE =
            "[note: OpenClaw gateway rejected tools/JSON schema; answered as plain text]\n";

    private final OpenAiClient inner;

    public OpenClawClient(HttpClient http, String baseUrl, String token, String agentId) {
        String base = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl).replaceAll("/+$", "");
        String agent = agentId == null || agentId.isBlank() ? "default" : agentId;
        this.inner = new OpenAiClient(http, base + "/v1/chat/completions", token, "openclaw/" + agent, "openclaw");
    }

    @Override public String providerName() { return "openclaw"; }
    @Override public String modelName()    { return inner.modelName(); }

    @Override
    public AgentResponse send(AgentRequest request) {
        var first = inner.send(request);
        boolean structured = request.hasTools() || request.wantsJson();
        if (first.ok() || !structured || !first.error().startsWith("[openclaw HTTP 400]")) return first;

        var plain = new AgentRequest(request.system(), request.messages(), List.of(), null, request.maxTokens());
        var second = inner.send(plain);
        if (!second.ok()) return second;
        return new AgentResponse(DEGRADED_NOTE + second.text(), second.toolCalls(), second.stopReason(),
                second.inputTokens(), second.outputTokens(), null);
    }

    JsonObject buildBody(AgentRequest request) { return inner.buildBody(request); }
}
