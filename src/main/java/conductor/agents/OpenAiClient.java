package conductor.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Map;

/**
 * OpenAI Chat Completions with function tools and JSON-schema output.
 * Also the engine behind {@link OpenClawClient}, which is why the provider
 * label used in error strings is injectable.
 */
public final class OpenAiClient implements AgentClient {

    public static final String DEFAULT_URL = "https://api.openai.com/v1/chat/completions";

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final String key;
    private final String model;
    private final String provider;

    public OpenAiClient(HttpClient http, String url, String apiKey, String model) {
        this(http, url, apiKey, model, "openai");
    }

    OpenAiClient(HttpClient http, String url, String apiKey, String model, String provider) {
        this.http = http;
        this.endpoint = URI.create(url == null || url.isBlank() ? DEFAULT_URL : url);
        this.key = apiKey == null ? "" : apiKey;
        this.headers = key.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + key);
        this.model = model;
        this.provider = provider;
    }

    @Override public String providerName() { return provider; }
    @Override public String modelName()    { return model; }

    @Override
    public AgentResponse send(AgentRequest request) {
        try {
            var response = Http.postJson(http, endpoint, headers, Json.GSON.toJson(buildBody(request)));
            if (response.statusCode() != 200) {
                return AgentResponse.error("[" + provider + " HTTP " + response.statusCode() + "] "
                        + Http.redactKeys(response.body(), key));
            }
            return parse(Json.parseObject(response.body()));
        } catch (IOException | RuntimeException e) {
            return AgentResponse.error("[" + provider + "] " + Http.redactKeys(String.valueOf(e.getMessage()), key));
        }
    }

    JsonObject buildBody(AgentRequest request) {
        var body = Json.of("model", model, "max_completion_tokens", request.maxTokens());
        var messages = new JsonArray();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(Json.of("role", "system", "content", request.system()));
        }
        for (var m : request.messages()) {
            if (m.hasToolResults()) {
                for (var r : m.toolResults()) {
                    messages.add(Json.of("role", "tool", "tool_call_id", r.callId(),
                            "content", r.isError() ? "ERROR: " + r.content() : r.content()));
                }
            } else if (m.hasToolCalls()) {
                var calls = new JsonArray();
                for (var c : m.toolCalls()) {
                    calls.add(Json.of("id", c.id(), "type", "function",
                            "function", Json.of("name", c.name(), "arguments", Json.GSON.toJson(c.arguments()))));
                }
                messages.add(Json.of("role", "assistant", "content", m.content(), "tool_calls", calls));
            } else {
                messages.add(Json.of("role", m.role(), "content", m.content()));
            }
        }
        body.add("messages", messages);

        if (request.hasTools()) {
            var tools = new JsonArray();
            for (var t : request.tools()) {
                tools.add(Json.of("type", "function", "function", Json.of("name", t.name(),
                        "description", t.description(), "parameters", Json.closedObjects(t.inputSchema()), "strict", true)));
            }
            body.add("tools", tools);
        }
        if (request.wantsJson()) {
            body.add("response_format", Json.of("type", "json_schema", "json_schema",
                    Json.of("name", "result", "schema", Json.closedObjects(request.outputSchema()), "strict", true)));
        }
        return body;
    }

    private static AgentResponse parse(JsonObject root) {
        var choices = Json.arr(root, "choices");
        if (choices.isEmpty()) return AgentResponse.error("Response contained no choices");
        var choice = choices.get(0).getAsJsonObject();
        var message = Json.obj(choice, "message");

        var calls = new ArrayList<ToolCall>();
        for (var el : Json.arr(message, "tool_calls")) {
            var call = el.getAsJsonObject();
            var fn = Json.obj(call, "function");
            calls.add(new ToolCall(Json.str(call, "id"), Json.str(fn, "name"), Json.parseObject(Json.str(fn, "arguments"))));
        }
        String refusal = Json.str(message, "refusal");
        String stop = switch (Json.str(choice, "finish_reason")) {
            case "tool_calls"     -> "tool_use";
            case "length"         -> "max_tokens";
            case "content_filter" -> "refusal";
            default               -> refusal.isBlank() ? "end_turn" : "refusal";
        };
        String text = Json.str(message, "content");
        if (stop.equals("refusal") && text.isBlank()) {
            text = refusal.isBlank() ? "The provider's content filter declined this request." : refusal;
        }
        var usage = Json.obj(root, "usage");
        return new AgentResponse(text, calls, stop,
                Json.num(usage, "prompt_tokens"), Json.num(usage, "completion_tokens"), null);
    }
}
