package conductor.agents;

import com.google.gson.JsonObject;

/**
 * A tool the model is allowed to call. {@code inputSchema} is a JSON Schema
 * object ({@code {"type":"object","properties":{...},"required":[...]}}).
 * Keep schemas small and {@code additionalProperties:false} so providers can
 * validate arguments strictly.
 */
public record ToolSpec(String name, String description, JsonObject inputSchema) {}
