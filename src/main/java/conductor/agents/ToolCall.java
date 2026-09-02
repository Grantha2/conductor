package conductor.agents;

import com.google.gson.JsonObject;

/**
 * The model's request to run one tool. {@code id} is provider-assigned and
 * must be echoed back in the matching {@link ToolResult}. {@code arguments}
 * is already parsed JSON — never string-match on it.
 */
public record ToolCall(String id, String name, JsonObject arguments) {}
