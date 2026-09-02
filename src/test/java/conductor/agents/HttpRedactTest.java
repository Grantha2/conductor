package conductor.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRedactTest {

    @Test
    void masksAnthropicOpenAiAndGoogleKeys() {
        String in = "bad key sk-ant-api03-AbCdEfGh1234 and AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY here";
        String out = Http.redactKeys(in);
        assertFalse(out.contains("AbCdEfGh1234"));
        assertFalse(out.contains("9tSrke72"));
        assertTrue(out.contains("sk-[redacted]"));
        assertTrue(out.contains("AIza[redacted]"));
        assertTrue(out.endsWith(" here"));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertEquals("risk-free", Http.redactKeys("risk-free"));
        assertEquals("", Http.redactKeys(null));
    }

    @Test
    void masksProjectKeysAndTheConfiguredSecretLiteral() {
        assertEquals("key sk-[redacted] end", Http.redactKeys("key sk-proj-AbCdEfGhIjKlMnOp end"));
        assertEquals("token [redacted] rejected", Http.redactKeys("token gw-t0ken rejected", "gw-t0ken"));
        assertEquals("plain", Http.redactKeys("plain", ""));
        assertEquals("plain", Http.redactKeys("plain", null));
        assertEquals("", Http.redactKeys(null, "x"));
    }
}
