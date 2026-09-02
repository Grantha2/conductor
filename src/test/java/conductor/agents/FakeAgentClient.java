package conductor.agents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted AgentClient for tests: replays queued responses in order and
 * records every request it saw. When the script runs dry it answers with a
 * deterministic "<name> #<n>" line so panel tests can identify who said what.
 */
public final class FakeAgentClient implements AgentClient {

    private final String name;
    private final Deque<AgentResponse> script = new ArrayDeque<>();
    public final List<AgentRequest> requests = new ArrayList<>();

    public FakeAgentClient(String name) { this.name = name; }

    public FakeAgentClient reply(String text) {
        script.add(new AgentResponse(text, List.of(), "end_turn", 1, 1, null));
        return this;
    }

    public FakeAgentClient replyToolCalls(String text, ToolCall... calls) {
        script.add(new AgentResponse(text, List.of(calls), "tool_use", 1, 1, null));
        return this;
    }

    public FakeAgentClient replyError(String message) {
        script.add(AgentResponse.error(message));
        return this;
    }

    @Override public String providerName() { return "fake"; }
    @Override public String modelName()    { return name; }

    @Override
    public AgentResponse send(AgentRequest request) {
        requests.add(request);
        var next = script.poll();
        return next != null ? next : new AgentResponse(name + " #" + requests.size(), List.of(), "end_turn", 1, 1, null);
    }
}
