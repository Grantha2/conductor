package conductor.agents;

import java.util.List;

/**
 * One turn in a conversation with an agent.
 *
 * Most turns are plain text ({@link #user}, {@link #assistant}). Two special
 * shapes exist so the tool-use loop can be written once in {@link AgentClient#run}
 * and each provider only translates to its own wire format:
 * <ul>
 *   <li>{@link #assistantToolCalls} — the model asked us to run tools.</li>
 *   <li>{@link #toolResults} — we ran them; here are the results.</li>
 * </ul>
 * Providers that don't support tools simply never see the special shapes.
 */
public record ChatMessage(
        String role,                 // "user" | "assistant"
        String content,              // text; may be "" on a pure tool-call turn
        List<ToolCall> toolCalls,    // non-empty only on an assistant tool-call turn
        List<ToolResult> toolResults // non-empty only on the user turn that answers tool calls
) {
    public static ChatMessage user(String text) {
        return new ChatMessage("user", text, List.of(), List.of());
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text, List.of(), List.of());
    }

    public static ChatMessage assistantToolCalls(String text, List<ToolCall> calls) {
        return new ChatMessage("assistant", text == null ? "" : text, List.copyOf(calls), List.of());
    }

    public static ChatMessage toolResults(List<ToolResult> results) {
        return new ChatMessage("user", "", List.of(), List.copyOf(results));
    }

    public boolean hasToolCalls()   { return toolCalls != null && !toolCalls.isEmpty(); }
    public boolean hasToolResults() { return toolResults != null && !toolResults.isEmpty(); }
}
