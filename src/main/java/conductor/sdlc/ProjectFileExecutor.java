package conductor.sdlc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import conductor.agents.ToolCall;
import conductor.agents.ToolExecutor;
import conductor.agents.ToolResult;
import conductor.agents.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lets a lead agent list, read and write files in one project's own workspace
 * directory ({@code <projectsDir>/<slug>/workspace/}). Every path argument is
 * resolved against that root and rejected if it would resolve outside it, so
 * a tool call can never touch anything else on disk.
 */
public final class ProjectFileExecutor implements ToolExecutor {

    private static final int MAX_READ_BYTES = 200_000;

    public static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("list_files", "List every file in the project workspace, as relative paths.",
                    schema(new JsonObject(), List.of())),
            new ToolSpec("read_file", "Read a text file from the project workspace.",
                    schema(props("path", "Relative path of the file to read."), List.of("path"))),
            new ToolSpec("write_file", "Write a text file to the project workspace, creating parent "
                    + "directories as needed and overwriting any existing file at that path.",
                    schema(props("path", "Relative path of the file to write.",
                                 "content", "Full text content to write."), List.of("path", "content")))
    );

    private final Path root;

    public ProjectFileExecutor(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.name()) {
                case "list_files" -> list(call);
                case "read_file"  -> read(call);
                case "write_file" -> write(call);
                default -> ToolResult.error(call.id(), "Unknown tool: " + call.name());
            };
        } catch (IOException e) {
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    private ToolResult list(ToolCall call) throws IOException {
        if (!Files.isDirectory(root)) return ToolResult.ok(call.id(), "(workspace is empty)");
        try (Stream<Path> files = Files.walk(root)) {
            String listing = files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return ToolResult.ok(call.id(), listing.isBlank() ? "(workspace is empty)" : listing);
        }
    }

    private ToolResult read(ToolCall call) throws IOException {
        String path = str(call, "path");
        Path file = resolve(path);
        if (file == null) return ToolResult.error(call.id(), "Path escapes the project workspace: " + path);
        if (!Files.isRegularFile(file)) return ToolResult.error(call.id(), "No such file: " + path);
        if (Files.size(file) > MAX_READ_BYTES) {
            return ToolResult.error(call.id(), "File too large to read (> " + MAX_READ_BYTES + " bytes): " + path);
        }
        return ToolResult.ok(call.id(), Files.readString(file));
    }

    private ToolResult write(ToolCall call) throws IOException {
        String path = str(call, "path");
        Path file = resolve(path);
        if (file == null) return ToolResult.error(call.id(), "Path escapes the project workspace: " + path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, str(call, "content"));
        return ToolResult.ok(call.id(), "Wrote " + path);
    }

    /** Null when {@code path} is blank or would resolve outside the workspace root. */
    private Path resolve(String path) {
        if (path.isBlank()) return null;
        Path candidate = root.resolve(path).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private static String str(ToolCall call, String key) {
        var v = call.arguments().get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    private static JsonObject schema(JsonObject properties, List<String> required) {
        var s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", properties);
        var req = new JsonArray();
        required.forEach(req::add);
        s.add("required", req);
        s.addProperty("additionalProperties", false);
        return s;
    }

    private static JsonObject props(String name, String description) {
        var p = new JsonObject();
        p.add(name, field(description));
        return p;
    }

    private static JsonObject props(String name1, String description1, String name2, String description2) {
        var p = props(name1, description1);
        p.add(name2, field(description2));
        return p;
    }

    private static JsonObject field(String description) {
        var f = new JsonObject();
        f.addProperty("type", "string");
        f.addProperty("description", description);
        return f;
    }
}
