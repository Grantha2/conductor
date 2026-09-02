package conductor.agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicClientBodyTest {

    private final AnthropicClient client =
            new AnthropicClient(HttpClient.newHttpClient(), null, "sk-test-key-000000", "claude-opus-5");

    private static JsonObject schema() {
        var s = new JsonObject();
        s.addProperty("type", "object");
        s.addProperty("additionalProperties", false);
        return s;
    }

    @Test
    void systemIsCachedArrayAndToolsAreStrict() {
        var tools = List.of(new ToolSpec("a", "first", schema()), new ToolSpec("b", "second", schema()));
        var body = client.buildBody(new AgentRequest("be terse", List.of(ChatMessage.user("hi")), tools, schema(), 500));

        assertEquals("claude-opus-5", body.get("model").getAsString());
        assertEquals(500, body.get("max_tokens").getAsInt());

        var system = body.getAsJsonArray("system");
        assertEquals(1, system.size());
        var block = system.get(0).getAsJsonObject();
        assertEquals("text", block.get("type").getAsString());
        assertEquals("be terse", block.get("text").getAsString());
        assertEquals("ephemeral", block.getAsJsonObject("cache_control").get("type").getAsString());

        var toolArr = body.getAsJsonArray("tools");
        assertEquals(2, toolArr.size());
        assertTrue(toolArr.get(0).getAsJsonObject().get("strict").getAsBoolean());
        assertTrue(toolArr.get(0).getAsJsonObject().has("input_schema"));
        assertFalse(toolArr.get(0).getAsJsonObject().has("cache_control"));
        assertTrue(toolArr.get(1).getAsJsonObject().has("cache_control"));

        var format = body.getAsJsonObject("output_config").getAsJsonObject("format");
        assertEquals("json_schema", format.get("type").getAsString());
        assertTrue(format.has("schema"));
        assertFalse(body.has("output_format"));
        assertFalse(body.has("tool_choice"));

        for (String banned : List.of("temperature", "top_p", "top_k", "thinking")) assertFalse(body.has(banned), banned);
    }

    @Test
    void blankSystemAndNoToolsOmitsOptionalFields() {
        var body = client.buildBody(AgentRequest.text("  ", List.of(ChatMessage.user("hi")), 10));
        assertFalse(body.has("system"));
        assertFalse(body.has("tools"));
        assertFalse(body.has("output_config"));
        assertEquals("hi", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void toolTurnsMapToContentBlocks() {
        var args = new JsonObject();
        args.addProperty("q", "x");
        var history = List.of(
                ChatMessage.user("go"),
                ChatMessage.assistantToolCalls("thinking", List.of(new ToolCall("tu_1", "a", args), new ToolCall("tu_2", "b", args))),
                ChatMessage.toolResults(List.of(ToolResult.ok("tu_1", "one"), ToolResult.error("tu_2", "bad"))));
        var messages = client.buildBody(AgentRequest.text(null, history, 10)).getAsJsonArray("messages");

        var assistant = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        var content = assistant.getAsJsonArray("content");
        assertEquals(3, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("tu_1", content.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("x", content.get(1).getAsJsonObject().getAsJsonObject("input").get("q").getAsString());

        var results = messages.get(2).getAsJsonObject();
        assertEquals("user", results.get("role").getAsString());
        var blocks = results.getAsJsonArray("content");
        assertEquals(2, blocks.size(), "all results in ONE user message");
        assertEquals("tool_result", blocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tu_1", blocks.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertFalse(blocks.get(0).getAsJsonObject().has("is_error"));
        assertTrue(blocks.get(1).getAsJsonObject().get("is_error").getAsBoolean());
    }

    @Test
    void blankTextOnToolCallTurnSendsNoEmptyTextBlock() {
        var history = List.of(
                ChatMessage.user("go"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("tu_1", "a", new JsonObject()))),
                ChatMessage.toolResults(List.of(ToolResult.ok("tu_1", "one"))));
        var messages = client.buildBody(AgentRequest.text(null, history, 10)).getAsJsonArray("messages");

        var content = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals(1, content.size(), "an empty text block would be a 400");
        assertEquals("tool_use", content.get(0).getAsJsonObject().get("type").getAsString());
        for (int i = 0; i < 3; i++) {
            assertEquals(i == 1 ? "assistant" : "user", messages.get(i).getAsJsonObject().get("role").getAsString(), "roles alternate");
        }
    }

    @Test
    void nestedSchemaObjectsAreClosedForStrictMode() {
        var plan = JsonParser.parseString("""
                {"type":"object","additionalProperties":false,"required":["tasks"],
                 "properties":{"tasks":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}}}}""")
                .getAsJsonObject();
        var body = client.buildBody(AgentRequest.json("s", List.of(ChatMessage.user("hi")), plan, 10));
        var schema = body.getAsJsonObject("output_config").getAsJsonObject("format").getAsJsonObject("schema");
        var items = schema.getAsJsonObject("properties").getAsJsonObject("tasks").getAsJsonObject("items");
        assertFalse(items.get("additionalProperties").getAsBoolean(), "inner object closed");
        assertEquals(1, schema.getAsJsonArray("required").size(), "existing keys untouched");
        assertFalse(plan.getAsJsonObject("properties").getAsJsonObject("tasks").getAsJsonObject("items").has("additionalProperties"),
                "the caller's schema is not mutated");
    }

    private static AgentResponse sendWith(String responseBody) {
        var stub = new StubHttpClient().reply(200, responseBody);
        return new AnthropicClient(stub, null, "sk-test-key-000000", "claude-opus-5")
                .send(AgentRequest.text("s", List.of(ChatMessage.user("q")), 10));
    }

    @Test
    void toolUseRefusalAndMalformedBodiesParseWithoutThrowing() {
        var tool = sendWith("""
                {"content":[{"type":"text","text":""},{"type":"tool_use","id":"tu_1","name":"a","input":"not-an-object"}],
                 "stop_reason":"tool_use","usage":{"input_tokens":7,"output_tokens":2}}""");
        assertTrue(tool.ok() && tool.wantsTools(), tool.error());
        assertEquals("tool_use", tool.stopReason());
        assertEquals(0, tool.toolCalls().get(0).arguments().size(), "non-object input degrades to {}");
        assertEquals(7, tool.inputTokens());
        assertEquals(2, tool.outputTokens());

        var refusal = sendWith("""
                {"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"nope"}}""");
        assertTrue(refusal.ok() && refusal.refused());
        assertTrue(refusal.text().contains("nope"), refusal.text());

        assertTrue(sendWith("{}").ok(), "missing content is an empty answer, not a crash");
        var malformed = assertDoesNotThrow(() -> sendWith("{\"content\":[\"not a block\"]}"));
        assertFalse(malformed.ok(), "a malformed block becomes an error response, not an exception");
    }
}
