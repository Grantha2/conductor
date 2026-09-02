package conductor.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Google Gemini {@code generateContent}. The API key travels in the
 * {@code x-goog-api-key} header, never the URL, so it cannot leak into logs.
 * Gemini keys tool results by function NAME and assigns no call ids, so ids
 * are minted client-side and mapped back to names when replaying history.
 */
public final class GeminiClient implements AgentClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final String key;
    private final String model;

    public GeminiClient(HttpClient http, String apiKey, String model) {
        this.http = http;
        this.endpoint = URI.create(BASE + model + ":generateContent");
        this.key = apiKey == null ? "" : apiKey;
        this.headers = Map.of("x-goog-api-key", key);
        this.model = model;
    }

    @Override public String providerName() { return "gemini"; }
    @Override public String modelName()    { return model; }

    URI endpoint() { return endpoint; }
    Map<String, String> headers() { return headers; }

    @Override
    public AgentResponse send(AgentRequest request) {
        try {
            var response = Http.postJson(http, endpoint, headers, Json.GSON.toJson(buildBody(request)));
            if (response.statusCode() != 200) {
                return AgentResponse.error("[gemini HTTP " + response.statusCode() + "] "
                        + Http.redactKeys(response.body(), key));
            }
            return parse(Json.parseObject(response.body()));
        } catch (IOException | RuntimeException e) {
            return AgentResponse.error("[gemini] " + Http.redactKeys(String.valueOf(e.getMessage()), key));
        }
    }

    JsonObject buildBody(AgentRequest request) {
        var body = new JsonObject();
        if (request.system() != null && !request.system().isBlank()) {
            body.add("system_instruction", Json.of("parts", Json.arrayOf(Json.of("text", request.system()))));
        }
        var contents = new JsonArray();
        var callNames = new HashMap<String, String>();
        for (var m : request.messages()) {
            var parts = new JsonArray();
            String role = "user";
            if (m.hasToolResults()) {
                for (var r : m.toolResults()) {
                    parts.add(Json.of("functionResponse", Json.of(
                            "name", callNames.getOrDefault(r.callId(), r.callId()),
                            "response", Json.of("content", r.isError() ? "ERROR: " + r.content() : r.content()))));
                }
            } else if (m.hasToolCalls()) {
                role = "model";
                if (!m.content().isBlank()) parts.add(Json.of("text", m.content()));
                for (var c : m.toolCalls()) {
                    callNames.put(c.id(), c.name());
                    parts.add(Json.of("functionCall", Json.of("name", c.name(), "args", c.arguments().deepCopy())));
                }
            } else {
                role = "assistant".equals(m.role()) ? "model" : "user";
                parts.add(Json.of("text", m.content()));
            }
            contents.add(Json.of("role", role, "parts", parts));
        }
        body.add("contents", contents);

        if (request.hasTools()) {
            var declarations = new JsonArray();
            for (var t : request.tools()) {
                declarations.add(Json.of("name", t.name(), "description", t.description(),
                        "parameters", Json.withoutAdditionalProperties(t.inputSchema())));
            }
            body.add("tools", Json.arrayOf(Json.of("functionDeclarations", declarations)));
        }
        var generation = Json.of("maxOutputTokens", request.maxTokens());
        if (request.wantsJson()) {
            generation.addProperty("responseMimeType", "application/json");
            generation.add("responseSchema", Json.withoutAdditionalProperties(request.outputSchema()));
        }
        body.add("generationConfig", generation);
        return body;
    }

    private static AgentResponse parse(JsonObject root) {
        var usage = Json.obj(root, "usageMetadata");
        int in = Json.num(usage, "promptTokenCount");
        int out = Json.num(usage, "candidatesTokenCount");
        var candidates = Json.arr(root, "candidates");
        if (candidates.isEmpty()) {
            String reason = Json.str(Json.obj(root, "promptFeedback"), "blockReason");
            return new AgentResponse("The model declined this request" + (reason.isBlank() ? "." : " (" + reason + ")."),
                    List.of(), "refusal", in, out, null);
        }
        var candidate = candidates.get(0).getAsJsonObject();
        var text = new StringBuilder();
        var calls = new ArrayList<ToolCall>();
        for (var el : Json.arr(Json.obj(candidate, "content"), "parts")) {
            var part = el.getAsJsonObject();
            if ("true".equals(Json.str(part, "thought"))) continue;   // thinking summary, not the answer
            if (part.has("functionCall")) {
                var fc = Json.obj(part, "functionCall");
                String name = Json.str(fc, "name"), id = Json.str(fc, "id");
                calls.add(new ToolCall(id.isBlank() ? name + "-" + UUID.randomUUID().toString().substring(0, 8) : id,
                        name, Json.obj(fc, "args")));
            } else {
                text.append(Json.str(part, "text"));
            }
        }
        String finish = Json.str(candidate, "finishReason");
        String stop = switch (finish) {
            case "MAX_TOKENS" -> "max_tokens";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "refusal";
            default -> calls.isEmpty() ? "end_turn" : "tool_use";
        };
        if (stop.equals("refusal") && text.isEmpty()) text.append("The model declined this request (").append(finish).append(").");
        return new AgentResponse(text.toString(), calls, stop, in, out, null);
    }
}
