package conductor.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One seat on the panel: a display name, a one-line perspective and the
 * longer lens that shapes how that agent argues. Persisted as a JSON array
 * so users can rename or re-aim panelists without touching code.
 */
public record Panelist(String name, String perspective, String lens) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Gson passes null for fields missing from the JSON; callers (prompts, listeners) must never see one. */
    public Panelist {
        name = name == null ? "" : name;
        perspective = perspective == null ? "" : perspective;
        lens = lens == null ? "" : lens;
    }

    /** Prompt-ready identity block, prepended to the system context for every call. */
    public String briefing() {
        return "=== YOUR AGENT IDENTITY ===\n"
                + "Agent name: " + name + "\n"
                + "Perspective: " + perspective + "\n"
                + lens + "\n\n";
    }

    /** Reads the panel from disk; any problem (missing, unreadable, empty, a seat without a name) yields {@link #defaults()}. */
    public static List<Panelist> loadAll(Path file) {
        try {
            if (!Files.isRegularFile(file)) return defaults();
            Panelist[] loaded = GSON.fromJson(Files.readString(file), Panelist[].class);
            if (loaded == null || loaded.length == 0) return defaults();
            for (Panelist p : loaded) if (p == null || p.name().isBlank()) return defaults();
            return List.of(loaded);
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public static void saveAll(Path file, List<Panelist> panelists) {
        try {
            Files.writeString(file, GSON.toJson(panelists));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save panel to " + file, e);
        }
    }

    public static List<Panelist> defaults() {
        return List.of(
                new Panelist("Claude", "Architecture & Quality",
                        "Priorities: structural soundness, long-term maintainability, "
                                + "edge cases, and failure modes. Push back when a proposal "
                                + "ignores reversibility or hides complexity. Prefer clear "
                                + "interfaces and explicit trade-offs over clever shortcuts."),
                new Panelist("GPT", "Ideas & Possibilities",
                        "Priorities: surfacing options the team hasn't considered, "
                                + "reframing the problem, and connecting the current question "
                                + "to adjacent opportunities. Push back when the group "
                                + "converges too early. Offer at least one alternative framing "
                                + "before endorsing the default."),
                new Panelist("Gemini", "Execution & Delivery",
                        "Priorities: what it takes to actually ship. Concrete next steps, "
                                + "owners, dependencies, and realistic sequencing. Push back "
                                + "when a plan skips the unglamorous work. Flag anything that "
                                + "would block execution in the next 1-2 weeks."));
    }
}
