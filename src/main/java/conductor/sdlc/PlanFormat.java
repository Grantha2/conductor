package conductor.sdlc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The PLAN artifact's two faces: Markdown for people, JSON for machines. The
 * lead answers the plan schema with JSON; we render a checklist grouped by
 * size and keep the raw JSON in a fenced block so VERIFY (and any engineering
 * agent) can read the tasks back without re-parsing prose.
 */
final class PlanFormat {

    static final JsonObject SCHEMA = JsonParser.parseString("""
            {"type":"object","additionalProperties":false,"required":["tasks"],
             "properties":{"tasks":{"type":"array","items":{
               "type":"object","required":["id","title","description","size"],
               "properties":{
                 "id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},
                 "size":{"type":"string","enum":["S","M","L"]},
                 "dependsOn":{"type":"array","items":{"type":"string"}}}}}}}
            """).getAsJsonObject();

    private static final String FENCE = "\n\n```json\n";
    private static final List<String> SIZES = List.of("S", "M", "L");

    private PlanFormat() {}

    /** Checklist + raw JSON; or the raw text under a warning header when it is not a valid plan. */
    static String artifact(String raw) {
        List<JsonObject> tasks = parseTasks(raw);
        if (tasks == null) return "# Plan (could not parse as JSON)\n\n" + raw;
        StringBuilder sb = new StringBuilder("# Plan\n");
        for (String size : List.of("S", "M", "L", "?")) {
            List<JsonObject> group = tasks.stream().filter(t -> size.equals(sizeOf(t))).toList();
            if (group.isEmpty()) continue;
            sb.append("\n## ").append(label(size)).append('\n');
            for (JsonObject t : group) {
                sb.append("- [ ] **").append(str(t, "id")).append("** ").append(str(t, "title"));
                List<String> deps = deps(t);
                if (!deps.isEmpty()) sb.append(" _(after ").append(String.join(", ", deps)).append(")_");
                sb.append('\n');
                if (!str(t, "description").isBlank()) sb.append("  ").append(str(t, "description")).append('\n');
            }
        }
        return sb + FENCE + raw + "\n```";
    }

    /** Task titles from an artifact produced by {@link #artifact}; empty when there is no readable plan. */
    static List<String> titles(String planArtifact) {
        int start = planArtifact.lastIndexOf(FENCE);
        int end = planArtifact.lastIndexOf("\n```");
        if (start < 0 || end <= start) return List.of();
        List<JsonObject> tasks = parseTasks(planArtifact.substring(start + FENCE.length(), end));
        if (tasks == null) return List.of();
        return tasks.stream().map(t -> str(t, "title")).filter(t -> !t.isBlank()).toList();
    }

    private static List<JsonObject> parseTasks(String raw) {
        try {
            JsonArray arr = JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("tasks");
            List<JsonObject> out = new ArrayList<>();
            for (JsonElement e : arr) out.add(e.getAsJsonObject());
            return out;
        } catch (RuntimeException e) {   // bad JSON, not an object, no "tasks" array, non-object items
            return null;
        }
    }

    private static String sizeOf(JsonObject t) {
        String s = str(t, "size").toUpperCase();
        return SIZES.contains(s) ? s : "?";
    }

    private static String label(String size) {
        return switch (size) {
            case "S" -> "Small tasks";
            case "M" -> "Medium tasks";
            case "L" -> "Large tasks";
            default -> "Unsized tasks";
        };
    }

    private static List<String> deps(JsonObject t) {
        List<String> out = new ArrayList<>();
        if (t.get("dependsOn") instanceof JsonArray arr) {
            for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static String str(JsonObject t, String key) {
        JsonElement e = t.get(key);
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
