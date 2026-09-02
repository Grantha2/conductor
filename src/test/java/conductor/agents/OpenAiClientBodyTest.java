package conductor.agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientBodyTest {

    private final OpenAiClient client =
            new OpenAiClient(HttpClient.newHttpClient(), null, "sk-test-key-000000", "gpt-5.4-mini");

    private static JsonObject schema() {
        var s = new JsonObject();
        s.addProperty("type", "object");
        return s;
    }

    @Test
    void systemLeadsMessagesAndToolsAreFunctions() {
        var req = new AgentRequest("sys", List.of(ChatMessage.user("hi")),
                List.of(new ToolSpec("lookup", "d", schema())), schema(), 300);
        var body = client.buildBody(req);

        assertEquals("gpt-5.4-mini", body.get("model").getAsString());
        assertEquals(300, body.get("max_completion_tokens").getAsInt());
        assertFalse(body.has("max_tokens"));

        var messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("sys", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());

        var tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        var fn = tool.getAsJsonObject("function");
        assertEquals("lookup", fn.get("name").getAsString());
        assertTrue(fn.has("parameters"));
        assertTrue(fn.get("strict").getAsBoolean());

        var rf = body.getAsJsonObject("response_format");
        assertEquals("json_schema", rf.get("type").getAsString());
        var js = rf.getAsJsonObject("json_schema");
        assertEquals("result", js.get("name").getAsString());
        assertTrue(js.get("strict").getAsBoolean());
        assertTrue(js.has("schema"));
    }

    @Test
    void toolCallArgumentsAreStringsAndResultsAreSeparateMessages() {
        var args = new JsonObject();
        args.addProperty("q", "x");
        var history = List.of(
                ChatMessage.user("go"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("call_1", "lookup", args))),
                ChatMessage.toolResults(List.of(ToolResult.ok("call_1", "one"), ToolResult.error("call_2", "bad"))));
        var messages = client.buildBody(AgentRequest.text(null, history, 10)).getAsJsonArray("messages");

        assertEquals(4, messages.size(), "user + assistant + one tool message PER result");
        var assistant = messages.get(1).getAsJsonObject();
        var call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("function", call.get("type").getAsString());
        var arguments = call.getAsJsonObject("function").get("arguments");
        assertTrue(arguments.isJsonPrimitive() && arguments.getAsJsonPrimitive().isString(), "arguments is a JSON string");
        assertEquals("x", JsonParser.parseString(arguments.getAsString()).getAsJsonObject().get("q").getAsString());

        var first = messages.get(2).getAsJsonObject();
        assertEquals("tool", first.get("role").getAsString());
        assertEquals("call_1", first.get("tool_call_id").getAsString());
        assertEquals("one", first.get("content").getAsString());
        assertEquals("ERROR: bad", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void strictSchemasGetAdditionalPropertiesFalseOnEveryObject() {
        var nested = JsonParser.parseString("""
                {"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}}}}""")
                .getAsJsonObject();
        var body = client.buildBody(new AgentRequest("s", List.of(ChatMessage.user("hi")),
                List.of(new ToolSpec("t", "d", nested)), nested, 10));

        var params = body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertFalse(params.get("additionalProperties").getAsBoolean());
        assertFalse(innerItems(params).get("additionalProperties").getAsBoolean(), "nested objects are closed too");
        var schema = body.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertFalse(innerItems(schema).get("additionalProperties").getAsBoolean());
        assertFalse(nested.has("additionalProperties"), "the caller's schema is not mutated");
    }

    private static JsonObject innerItems(JsonObject schema) {
        return schema.getAsJsonObject("properties").getAsJsonObject("items").getAsJsonObject("items");
    }

    private static AgentResponse sendWith(String responseBody) {
        var stub = new StubHttpClient().reply(200, responseBody);
        return new OpenAiClient(stub, null, "sk-test-key-000000", "gpt-5.4-mini")
                .send(AgentRequest.text("s", List.of(ChatMessage.user("q")), 10));
    }

    @Test
    void nullContentOnToolCallTurnParsesCleanly() {
        var r = sendWith("""
                {"choices":[{"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":3,"completion_tokens":4}}""");
        assertTrue(r.ok(), r.error());
        assertEquals("", r.text());
        assertEquals("tool_use", r.stopReason());
        assertTrue(r.wantsTools());
        assertEquals("call_1", r.toolCalls().get(0).id());
        assertEquals("lookup", r.toolCalls().get(0).name());
        assertEquals("x", r.toolCalls().get(0).arguments().get("q").getAsString());
        assertEquals(3, r.inputTokens());
        assertEquals(4, r.outputTokens());
    }

    @Test
    void malformedResponsesNeverThrow() {
        var badArgs = sendWith("""
                {"choices":[{"message":{"content":null,"tool_calls":[{"id":"c","type":"function",
                   "function":{"name":"lookup","arguments":"not json"}}]},"finish_reason":"tool_calls"}]}""");
        assertTrue(badArgs.ok());
        assertEquals(0, badArgs.toolCalls().get(0).arguments().size(), "unparseable arguments degrade to {}");

        var filtered = sendWith("{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"content_filter\"}]}");
        assertTrue(filtered.ok() && filtered.refused());
        assertFalse(filtered.text().isBlank(), "a refusal always carries readable text");

        for (String body : List.of("{\"choices\":[]}", "{}", "[]", "not json at all", "{\"choices\":[\"not an object\"]}")) {
            var r = assertDoesNotThrow(() -> sendWith(body), body);
            assertFalse(r.ok(), body);
        }
    }
}
