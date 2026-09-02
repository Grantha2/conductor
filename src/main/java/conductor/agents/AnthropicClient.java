package conductor.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Map;

/**
 * Anthropic Messages API ({@code POST /v1/messages}). Marks the system prompt
 * and tool list as cacheable, uses {@code output_config} for structured
 * output, and deliberately sends no sampling parameters, thinking budget or
 * assistant prefill — current Claude models reject those with HTTP 400.
 */
public final class AnthropicClient implements AgentClient {

    public static final String DEFAULT_URL = "https://api.anthropic.com";

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final String key;
    private final String model;

    public AnthropicClient(HttpClient http, String url, String apiKey, String model) {
        this.http = http;
        String base = (url == null || url.isBlank() ? DEFAULT_URL : url).replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/v1/messages");
        this.key = apiKey == null ? "" : apiKey;
        this.headers = Map.of("x-api-key", key, "anthropic-version", "2023-06-01");
        this.model = model;
    }

    @Override public String providerName() { return "anthropic"; }
    @Override public String modelName()    { return model; }

    @Override
    public AgentResponse send(AgentRequest request) {
        try {
            var response = Http.postJson(http, endpoint, headers, Json.GSON.toJson(buildBody(request)));
            if (response.statusCode() != 200) {
                return AgentResponse.error("[anthropic HTTP " + response.statusCode() + "] "
                        + Http.redactKeys(response.body(), key));
            }
            return parse(Json.parseObject(response.body()));
        } catch (IOException | RuntimeException e) {
            return AgentResponse.error("[anthropic] " + Http.redactKeys(String.valueOf(e.getMessage()), key));
        }
    }

    JsonObject buildBody(AgentRequest request) {
        var body = Json.of("model", model, "max_tokens", request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            body.add("system", Json.arrayOf(
                    Json.of("type", "text", "text", request.system(), "cache_control", ephemeral())));
        }
        var messages = new JsonArray();
        for (var m : request.messages()) messages.add(toMessage(m));
        body.add("messages", messages);

        if (request.hasTools()) {
            var tools = new JsonArray();
            for (var t : request.tools()) {
                tools.add(Json.of("name", t.name(), "description", t.description(),
                        "input_schema", Json.closedObjects(t.inputSchema()), "strict", true));
            }
            // Tools precede system in the cache prefix; one breakpoint on the last tool covers them all.
            tools.get(tools.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
            body.add("tools", tools);
        }
        if (request.wantsJson()) {
            body.add("output_config", Json.of("format",
                    Json.of("type", "json_schema", "schema", Json.closedObjects(request.outputSchema()))));
        }
        return body;
    }

    private static JsonObject toMessage(ChatMessage m) {
        if (m.hasToolResults()) {
            var content = new JsonArray();
            for (var r : m.toolResults()) {
                var block = Json.of("type", "tool_result", "tool_use_id", r.callId(), "content", r.content());
                if (r.isError()) block.addProperty("is_error", true);
                content.add(block);
            }
            return Json.of("role", "user", "content", content);
        }
        if (m.hasToolCalls()) {
            var content = new JsonArray();
            if (!m.content().isBlank()) content.add(Json.of("type", "text", "text", m.content()));
            for (var c : m.toolCalls()) {
                content.add(Json.of("type", "tool_use", "id", c.id(), "name", c.name(), "input", c.arguments().deepCopy()));
            }
            return Json.of("role", "assistant", "content", content);
        }
        return Json.of("role", m.role(), "content", m.content());
    }

    private static AgentResponse parse(JsonObject root) {
        var text = new StringBuilder();
        var calls = new ArrayList<ToolCall>();
        for (var el : Json.arr(root, "content")) {
            var block = el.getAsJsonObject();
            switch (Json.str(block, "type")) {
                case "text" -> text.append(Json.str(block, "text"));
                case "tool_use" -> calls.add(new ToolCall(
                        Json.str(block, "id"), Json.str(block, "name"), Json.obj(block, "input")));
                default -> { }
            }
        }
        String stop = switch (Json.str(root, "stop_reason")) {
            case "tool_use"   -> "tool_use";
            case "max_tokens" -> "max_tokens";
            case "refusal"    -> "refusal";
            default           -> "end_turn";
        };
        if (stop.equals("refusal")) {
            String why = Json.str(Json.obj(root, "stop_details"), "explanation");
            text.insert(0, "The model declined this request" + (why.isBlank() ? "." : ": " + why) + "\n");
        }
        var usage = Json.obj(root, "usage");
        return new AgentResponse(text.toString().strip(), calls, stop,
                Json.num(usage, "input_tokens"), Json.num(usage, "output_tokens"), null);
    }

    private static JsonObject ephemeral() { return Json.of("type", "ephemeral"); }
}
