package conductor.agents;

import java.util.List;

/**
 * What came back from one agent call.
 *
 * <p>Check {@link #ok()} first. When it is false, {@code error} explains why
 * and every other field is empty/zero. Clients never throw on API failures;
 * they return an error response so a single failed panelist does not abort
 * a whole stage.
 *
 * <p>{@code stopReason} is normalised across providers to one of:
 * {@code end_turn}, {@code tool_use}, {@code max_tokens}, {@code refusal},
 * {@code error}.
 */
public record AgentResponse(
        String text,
        List<ToolCall> toolCalls,
        String stopReason,
        int inputTokens,
        int outputTokens,
        String error
) {
    public AgentResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        stopReason = stopReason == null ? (error == null ? "end_turn" : "error") : stopReason;
    }

    public static AgentResponse error(String message) {
        return new AgentResponse("", List.of(), "error", 0, 0, message);
    }

    public boolean ok()         { return error == null; }
    public boolean wantsTools() { return !toolCalls.isEmpty(); }
    public boolean truncated()  { return "max_tokens".equals(stopReason); }
    public boolean refused()    { return "refusal".equals(stopReason); }
}
