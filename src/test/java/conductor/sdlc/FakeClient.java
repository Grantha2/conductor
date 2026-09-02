package conductor.sdlc;

import conductor.agents.AgentClient;
import conductor.agents.AgentRequest;
import conductor.agents.AgentResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted AgentClient for tests: replays the given replies in order (the
 * last one repeats forever) and records every request so tests can assert
 * on call counts, system context and prompts.
 */
final class FakeClient implements AgentClient {

    final List<AgentRequest> sent = new ArrayList<>();
    private final String name;
    private final Deque<String> replies;
    private final String error;

    FakeClient(String name, String... replies) { this(name, null, List.of(replies)); }

    private FakeClient(String name, String error, List<String> replies) {
        this.name = name;
        this.error = error;
        this.replies = new ArrayDeque<>(replies);
    }

    /** A client whose every call fails with {@code error}. */
    static FakeClient failing(String name, String error) { return new FakeClient(name, error, List.of()); }

    @Override public String providerName() { return name; }

    @Override public String modelName() { return name + "-model"; }

    @Override public AgentResponse send(AgentRequest request) {
        sent.add(request);
        if (error != null) return AgentResponse.error(error);
        String reply = replies.size() > 1 ? replies.poll() : replies.peekFirst();
        return new AgentResponse(reply == null ? "" : reply, List.of(), "end_turn", 0, 0, null);
    }
}
