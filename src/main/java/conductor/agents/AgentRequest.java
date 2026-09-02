package conductor.agents;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Everything one call to an agent needs.
 *
 * <p>{@code system} holds the stable instructions (identity, org context,
 * prior conclusions). Put the stuff that changes every call in
 * {@code messages}, not here — providers cache the system prefix when it is
 * byte-identical between calls, and a timestamp or per-call ID in the system
 * text silently defeats that.
 *
 * <p>{@code outputSchema} non-null means "answer with JSON matching this
 * schema". Each provider maps that to its own structured-output mechanism.
 * Null means free text.
 */
public record AgentRequest(
        String system,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        JsonObject outputSchema,
        int maxTokens
) {
    public AgentRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("AgentRequest needs at least one message");
        }
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Plain text in, plain text out. The common case. */
    public static AgentRequest text(String system, List<ChatMessage> messages, int maxTokens) {
        return new AgentRequest(system, messages, List.of(), null, maxTokens);
    }

    /** Ask for JSON that matches {@code schema}. */
    public static AgentRequest json(String system, List<ChatMessage> messages,
                                    JsonObject schema, int maxTokens) {
        return new AgentRequest(system, messages, List.of(), schema, maxTokens);
    }

    /** Same request, different message history. Used by the tool loop. */
    public AgentRequest withMessages(List<ChatMessage> newMessages) {
        return new AgentRequest(system, newMessages, tools, outputSchema, maxTokens);
    }

    public boolean hasTools()  { return !tools.isEmpty(); }
    public boolean wantsJson() { return outputSchema != null; }
}
