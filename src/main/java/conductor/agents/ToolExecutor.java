package conductor.agents;

/**
 * Runs one tool call and returns its result. Implementations decide what a
 * tool actually does (read a file, query a store, ask the user). Must never
 * throw — wrap failures in {@link ToolResult#error}.
 */
@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(ToolCall call);
}
