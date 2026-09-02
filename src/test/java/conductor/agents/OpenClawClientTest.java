package conductor.agents;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenClawClientTest {

    @Test
    void routesToAgentViaModelField() {
        var client = new OpenClawClient(HttpClient.newHttpClient(), "http://gw:18789/", "tok", "research");
        assertEquals("openclaw", client.providerName());
        assertEquals("openclaw/research", client.modelName());
        var body = client.buildBody(AgentRequest.text("s", List.of(ChatMessage.user("hi")), 10));
        assertEquals("openclaw/research", body.get("model").getAsString());
    }

    @Test
    void blankAgentIdFallsBackToDefault() {
        var client = new OpenClawClient(HttpClient.newHttpClient(), null, "", "");
        assertEquals("openclaw/default", client.modelName());
    }
}
