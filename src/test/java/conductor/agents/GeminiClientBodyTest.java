package conductor.agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeminiClientBodyTest {

    private static final String KEY = "AIzaSyTESTKEY0000000000";
    private final GeminiClient client = new GeminiClient(HttpClient.newHttpClient(), KEY, "gemini-3.1-pro-preview");

    private static JsonObject schema() {
        var s = new JsonObject();
        s.addProperty("type", "object");
        return s;
    }

    @Test
    void keyTravelsInHeaderNotUrl() {
        assertFalse(client.endpoint().toString().contains(KEY));
        assertNull(client.endpoint().getQuery());
        assertTrue(client.endpoint().toString().endsWith("/models/gemini-3.1-pro-preview:generateContent"));
        assertEquals(KEY, client.headers().get("x-goog-api-key"));
    }

    @Test
    void rolesSystemToolsAndJsonModeMapToGeminiShapes() {
        var req = new AgentRequest("sys", List.of(ChatMessage.user("q"), ChatMessage.assistant("a"), ChatMessage.user("q2")),
                List.of(new ToolSpec("lookup", "d", schema())), schema(), 250);
        var body = client.buildBody(req);

        assertEquals("sys", body.getAsJsonObject("system_instruction").getAsJsonArray("parts")
                .get(0).getAsJsonObject().get("text").getAsString());

        var contents = body.getAsJsonArray("contents");
        assertEquals("user", contents.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("model", contents.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("a", contents.get(1).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());

        var decl = body.getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonArray("functionDeclarations").get(0).getAsJsonObject();
        assertEquals("lookup", decl.get("name").getAsString());
        assertTrue(decl.has("parameters"));

        var gen = body.getAsJsonObject("generationConfig");
        assertEquals(250, gen.get("maxOutputTokens").getAsInt());
        assertEquals("application/json", gen.get("responseMimeType").getAsString());
        assertTrue(gen.has("responseSchema"));
    }

    @Test
    void toolResultsAreKeyedByFunctionNameFromPrecedingCall() {
        var args = new JsonObject();
        args.addProperty("q", "x");
        var history = List.of(
                ChatMessage.user("go"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("lookup-abcd1234", "lookup", args))),
                ChatMessage.toolResults(List.of(ToolResult.ok("lookup-abcd1234", "one"))));
        var contents = client.buildBody(AgentRequest.text(null, history, 10)).getAsJsonArray("contents");

        var model = contents.get(1).getAsJsonObject();
        assertEquals("model", model.get("role").getAsString());
        var fc = model.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("functionCall");
        assertEquals("lookup", fc.get("name").getAsString());
        assertEquals("x", fc.getAsJsonObject("args").get("q").getAsString());

        var user = contents.get(2).getAsJsonObject();
        assertEquals("user", user.get("role").getAsString());
        var fr = user.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("functionResponse");
        assertEquals("lookup", fr.get("name").getAsString(), "result keyed by function name, not call id");
        assertEquals("one", fr.getAsJsonObject("response").get("content").getAsString());
    }

    @Test
    void additionalPropertiesIsStrippedFromToolAndResponseSchemas() {
        var strict = JsonParser.parseString("""
                {"type":"object","additionalProperties":false,
                 "properties":{"inner":{"type":"object","additionalProperties":false,"properties":{"q":{"type":"string"}}}}}""")
                .getAsJsonObject();
        var body = client.buildBody(new AgentRequest("s", List.of(ChatMessage.user("q")),
                List.of(new ToolSpec("lookup", "d", strict)), strict, 10));

        var params = body.getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonArray("functionDeclarations").get(0).getAsJsonObject().getAsJsonObject("parameters");
        assertFalse(params.has("additionalProperties"));
        assertFalse(params.getAsJsonObject("properties").getAsJsonObject("inner").has("additionalProperties"), "stripped at every depth");
        assertTrue(params.getAsJsonObject("properties").getAsJsonObject("inner").has("properties"), "everything else kept");
        assertFalse(body.getAsJsonObject("generationConfig").getAsJsonObject("responseSchema").has("additionalProperties"));
        assertTrue(strict.has("additionalProperties"), "the caller's schema is not mutated");
    }

    private static AgentResponse sendWith(String responseBody) {
        var stub = new StubHttpClient().reply(200, responseBody);
        return new GeminiClient(stub, KEY, "gemini-3.1-pro-preview")
                .send(AgentRequest.text("s", List.of(ChatMessage.user("q")), 10));
    }

    @Test
    void emptyCandidatesIsARefusalNotAnException() {
        var r = sendWith("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"usageMetadata\":{\"promptTokenCount\":5}}");
        assertTrue(r.ok());
        assertTrue(r.refused());
        assertTrue(r.text().contains("SAFETY"), r.text());
        assertEquals(5, r.inputTokens());

        var empty = sendWith("{}");
        assertTrue(empty.ok() && empty.refused() && !empty.text().isBlank());
        var malformed = assertDoesNotThrow(() -> sendWith("{\"candidates\":[42]}"));
        assertFalse(malformed.ok(), "a non-object candidate becomes an error response");
    }

    @Test
    void thoughtPartsAreSkippedAndFunctionCallsGetClientMintedIds() {
        var r = sendWith("""
                {"candidates":[{"content":{"role":"model","parts":[
                   {"text":"internal reasoning","thought":true},
                   {"functionCall":{"name":"lookup","args":{"q":"x"}}}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}}""");
        assertTrue(r.ok(), r.error());
        assertEquals("", r.text(), "thought summaries are not the answer");
        assertEquals("tool_use", r.stopReason());
        var call = r.toolCalls().get(0);
        assertEquals("lookup", call.name());
        assertTrue(call.id().startsWith("lookup-") && call.id().length() > "lookup-".length(), call.id());
        assertEquals("x", call.arguments().get("q").getAsString());
        assertEquals(2, r.outputTokens());
    }
}
