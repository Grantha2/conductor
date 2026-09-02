package conductor.sdlc;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything Conductor knows about one project. This is the object that is
 * saved to and loaded from {@code projects/<slug>.json} (see ProjectStore).
 *
 * <p>Three kinds of data:
 * <ul>
 *   <li>{@code answers}   — the user's own words, keyed by {@link Question#id}.</li>
 *   <li>{@code artifacts} — what a stage produced when it was completed
 *       (a requirements list, a design summary, a task plan, a build brief…).
 *       Stored as text (often Markdown, sometimes JSON).</li>
 *   <li>{@code current}   — which stage the user is on.</li>
 * </ul>
 * Deliberately a plain mutable class with Gson-friendly fields, not a record,
 * because the UI edits it in place as the user types.
 */
public class Project {

    private String name;
    private String slug;
    private String createdAt;
    private String updatedAt;
    private Stage current = Stage.IDEA;
    private Map<String, String> answers = new LinkedHashMap<>();
    private Map<Stage, String> artifacts = new EnumMap<>(Stage.class);

    /** Gson needs this. */
    public Project() {}

    public Project(String name) {
        this.name = name;
        this.slug = slugify(name);
        this.createdAt = Instant.now().toString();
        this.updatedAt = createdAt;
    }

    public String name()      { return name; }
    public String slug()      { return slug; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    public Stage current()    { return current; }

    public Map<String, String> answers()   { return answers; }
    public Map<Stage, String> artifacts()  { return artifacts; }

    public String answer(String questionId) {
        return answers.getOrDefault(questionId, "");
    }

    public void setAnswer(String questionId, String value) {
        answers.put(questionId, value == null ? "" : value);
        touch();
    }

    public String artifact(Stage stage) {
        return artifacts.getOrDefault(stage, "");
    }

    public void setArtifact(Stage stage, String value) {
        artifacts.put(stage, value == null ? "" : value);
        touch();
    }

    public void setCurrent(Stage stage) {
        this.current = stage;
        touch();
    }

    public boolean isComplete(Stage stage) {
        return !artifact(stage).isBlank();
    }

    private void touch() {
        this.updatedAt = Instant.now().toString();
    }

    /** "My Cool App!" → "my-cool-app". Safe for file names. */
    static String slugify(String s) {
        String slug = s == null ? "" : s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "project" : slug;
    }
}
