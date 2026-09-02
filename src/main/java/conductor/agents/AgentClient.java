package conductor.agents;

import java.util.ArrayList;
import java.util.List;

/**
 * The one contract every AI provider implements.
 *
 * <p>Implementations exist for Anthropic (Claude), OpenAI (GPT), Google
 * (Gemini), and OpenClaw (a self-hosted agent gateway that speaks the OpenAI
 * wire format). Everything above this interface — the debate panel, the SDLC
 * stages, the UI — is provider-blind.
 *
 * <p>Rules for implementers:
 * <ul>
 *   <li>{@link #send} is one HTTP round-trip. Blocking. No streaming.</li>
 *   <li>Never throw on API errors; return {@link AgentResponse#error}.</li>
 *   <li>Retry 408/409/429/5xx with exponential backoff; honour
 *       {@code retry-after} when present. Never retry 4xx client errors.</li>
 *   <li>Build JSON with Gson objects, never string concatenation.</li>
 *   <li>Never log the API key or the full request body at INFO level.</li>
 *   <li>If the provider supports prompt caching, mark the system prompt
 *       (and tool list) as cacheable — it is the stable prefix by design.</li>
 * </ul>
 */
public interface AgentClient {

    /** Short lowercase id: "anthropic", "openai", "gemini", "openclaw". */
    String providerName();

    /** The model or agent id this client is bound to, for display and logs. */
    String modelName();

    /** One request, one response. The only method a provider must implement. */
    AgentResponse send(AgentRequest request);

    /**
     * Run a tool-use loop until the model stops asking for tools, an error
     * occurs, or {@code maxIterations} is hit. Written once here so each
     * provider only has to translate {@link ChatMessage} tool shapes to its
     * wire format — the loop logic never diverges between providers.
     */
    default AgentResponse run(AgentRequest request, ToolExecutor executor, int maxIterations) {
        if (!request.hasTools() || executor == null) return send(request);

        List<ChatMessage> history = new ArrayList<>(request.messages());
        for (int i = 0; i < maxIterations; i++) {
            AgentResponse r = send(request.withMessages(history));
            if (!r.ok() || !r.wantsTools()) return r;

            history.add(ChatMessage.assistantToolCalls(r.text(), r.toolCalls()));
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : r.toolCalls()) {
                ToolResult res;
                try { res = executor.execute(call); }
                catch (RuntimeException e) { res = ToolResult.error(call.id(), e.getMessage()); }
                results.add(res);
            }
            history.add(ChatMessage.toolResults(results));
        }
        return AgentResponse.error("Tool loop exceeded " + maxIterations + " iterations");
    }
}
