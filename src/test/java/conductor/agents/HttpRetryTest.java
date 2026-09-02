package conductor.agents;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Drives Http.postJson's retry policy and the secret-scrubbing paths through a real client's send(). */
class HttpRetryTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":2}}""";
    private static final Map<String, String> NOW = Map.of("retry-after", "0");

    private static AgentRequest req() { return AgentRequest.text("s", List.of(ChatMessage.user("q")), 10); }

    private static OpenAiClient client(StubHttpClient stub) {
        return new OpenAiClient(stub, null, "sk-test-key-000000", "gpt-5.4-mini");
    }

    @Test
    void clientErrorsAreReturnedWithoutRetry() {
        for (int status : List.of(400, 401, 403, 404, 422)) {
            var stub = new StubHttpClient().reply(status, "{\"error\":\"nope\"}", NOW);
            var r = client(stub).send(req());
            assertFalse(r.ok());
            assertTrue(r.error().startsWith("[openai HTTP " + status + "]"), r.error());
            assertEquals(1, stub.requests.size(), "HTTP " + status + " must not be retried");
        }
    }

    @Test
    void transientStatusesAreRetriedThenParsed() {
        var stub = new StubHttpClient().reply(429, "slow down", NOW).reply(503, "", NOW).reply(200, OK_BODY);
        var r = client(stub).send(req());
        assertTrue(r.ok(), r.error());
        assertEquals("hi", r.text());
        assertEquals(1, r.inputTokens());
        assertEquals(2, r.outputTokens());
        assertEquals(3, stub.requests.size());
    }

    @Test
    void retriesAreBoundedAndTheLastResponseIsReported() {
        var stub = new StubHttpClient();
        for (int i = 0; i < 5; i++) stub.reply(500 + i, "boom " + i, NOW);
        var r = client(stub).send(req());
        assertFalse(r.ok());
        assertEquals(4, stub.requests.size(), "1 attempt + 3 retries, never more");
        assertTrue(r.error().startsWith("[openai HTTP 503] boom 3"), r.error());
    }

    @Test
    void nonNumericRetryAfterFallsBackToExponentialBackoff() {
        var stub = new StubHttpClient()
                .reply(429, "", Map.of("retry-after", "Wed, 21 Oct 2015 07:28:00 GMT"))
                .reply(200, OK_BODY);
        long start = System.nanoTime();
        var r = client(stub).send(req());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(r.ok(), r.error());
        assertEquals(2, stub.requests.size());
        assertTrue(millis >= 900 && millis < 30_000, "expected the 1s first backoff, took " + millis + "ms");
    }

    @Test
    void ioFailuresAreRetriedAndNeverEscapeSend() {
        var stub = new StubHttpClient().fail("connection reset").reply(200, OK_BODY);
        var r = assertDoesNotThrow(() -> client(stub).send(req()));
        assertTrue(r.ok(), r.error());
        assertEquals(2, stub.requests.size());
    }

    @Test
    void illegalHeaderCharacterInKeyIsReportedWithoutTheKey() {
        var stub = new StubHttpClient();
        String key = "sk-proj-ABCDEFGH…TAIL12345678";   // a pasted ellipsis is not a legal header character
        var r = new OpenAiClient(stub, null, key, "gpt-5.4-mini").send(req());
        assertFalse(r.ok());
        assertFalse(r.error().contains("TAIL12345678"), r.error());
        assertFalse(r.error().contains("ABCDEFGH"), r.error());
        assertTrue(r.error().contains("not allowed in an HTTP header"), r.error());
        assertEquals(0, stub.requests.size(), "nothing was sent");
    }

    @Test
    void configuredTokenIsScrubbedFromErrorBodiesEvenWhenNoKeyPatternMatches() {
        var stub = new StubHttpClient().reply(401, "{\"error\":\"bad token: my-gateway-token-42\"}");
        var gw = new OpenClawClient(stub, "http://gw:18789", "my-gateway-token-42", "research");
        var r = gw.send(req());
        assertFalse(r.ok());
        assertFalse(r.error().contains("my-gateway-token-42"), r.error());
        assertTrue(r.error().contains("[redacted]"), r.error());
        assertEquals(1, stub.requests.size(), "401 is not the 400 that triggers the plain-text fallback");
        assertEquals("Bearer my-gateway-token-42", stub.requests.get(0).headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void geminiKeyTravelsOnlyInTheHeader() {
        var stub = new StubHttpClient().reply(200, "{}");
        new GeminiClient(stub, "AIzaSyTESTKEY0000000000", "gemini-3.1-pro-preview").send(req());
        var sent = stub.requests.get(0);
        assertFalse(sent.uri().toString().contains("AIza"));
        assertEquals("AIzaSyTESTKEY0000000000", sent.headers().firstValue("x-goog-api-key").orElse(""));
        assertEquals("application/json", sent.headers().firstValue("content-type").orElse(""));
    }
}
