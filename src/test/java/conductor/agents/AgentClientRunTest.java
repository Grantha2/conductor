package conductor.agents;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the default tool loop in {@link AgentClient#run}. */
class AgentClientRunTest {

    private static final ToolSpec LOOKUP = new ToolSpec("lookup", "Look something up", new JsonObject());

    private static AgentRequest withTools() {
        return new AgentRequest("sys", List.of(ChatMessage.user("hi")), List.of(LOOKUP), null, 100);
    }

    @Test
    void noToolsMeansSingleSend() {
        var fake = new FakeAgentClient("a").reply("plain");
        var r = fake.run(AgentRequest.text("sys", List.of(ChatMessage.user("hi")), 100), c -> ToolResult.ok(c.id(), "x"), 5);
        assertEquals("plain", r.text());
        assertEquals(1, fake.requests.size());
    }

    @Test
    void oneToolCallRoundTripAppendsCallsThenResults() {
        var args = new JsonObject();
        args.addProperty("q", "weather");
        var fake = new FakeAgentClient("a")
                .replyToolCalls("checking", new ToolCall("call-1", "lookup", args))
                .reply("done");

        var r = fake.run(withTools(), c -> ToolResult.ok(c.id(), "42"), 5);

        assertTrue(r.ok());
        assertEquals("done", r.text());
        assertEquals(2, fake.requests.size());
        var history = fake.requests.get(1).messages();
        assertEquals(3, history.size());
        assertEquals("hi", history.get(0).content());
        assertTrue(history.get(1).hasToolCalls());
        assertEquals("checking", history.get(1).content());
        assertEquals("call-1", history.get(1).toolCalls().get(0).id());
        assertTrue(history.get(2).hasToolResults());
        assertEquals("call-1", history.get(2).toolResults().get(0).callId());
        assertEquals("42", history.get(2).toolResults().get(0).content());
    }

    @Test
    void executorExceptionBecomesErrorResult() {
        var fake = new FakeAgentClient("a")
                .replyToolCalls("", new ToolCall("c", "lookup", new JsonObject()))
                .reply("recovered");
        var r = fake.run(withTools(), c -> { throw new IllegalStateException("boom"); }, 5);
        assertEquals("recovered", r.text());
        var result = fake.requests.get(1).messages().get(2).toolResults().get(0);
        assertTrue(result.isError());
        assertEquals("boom", result.content());
    }

    @Test
    void exceedingMaxIterationsReturnsError() {
        var fake = new FakeAgentClient("a");
        for (int i = 0; i < 3; i++) fake.replyToolCalls("", new ToolCall("c" + i, "lookup", new JsonObject()));
        var r = fake.run(withTools(), c -> ToolResult.ok(c.id(), "x"), 2);
        assertFalse(r.ok());
        assertTrue(r.error().contains("exceeded"));
        assertEquals(2, fake.requests.size());
    }
}
