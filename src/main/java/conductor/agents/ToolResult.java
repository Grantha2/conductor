package conductor.agents;

/**
 * What we send back after running a tool. Always return one result per
 * {@link ToolCall}, even on failure — set {@code isError} and describe the
 * problem in {@code content} so the model can recover.
 */
public record ToolResult(String callId, String content, boolean isError) {
    public static ToolResult ok(String callId, String content) {
        return new ToolResult(callId, content == null ? "" : content, false);
    }

    public static ToolResult error(String callId, String message) {
        return new ToolResult(callId, message == null ? "unknown error" : message, true);
    }
}
