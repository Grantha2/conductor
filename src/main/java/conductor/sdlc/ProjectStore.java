package conductor.sdlc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Projects on disk: one pretty-printed JSON file per project at
 * {@code <dir>/<slug>.json}. Loading never throws - a missing or corrupt file
 * yields null plus one stderr line, so one bad file cannot take the UI down.
 * Saving does throw (unchecked): silently losing the user's answers would be
 * worse than a visible error.
 */
public final class ProjectStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;

    public ProjectStore() { this(Path.of("projects")); }

    public ProjectStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    public List<String> listSlugs() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("ProjectStore: cannot list " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    public boolean exists(String slug) { return Files.isRegularFile(file(slug)); }

    /** Null when the file is missing or cannot be read as a project. */
    public Project load(String slug) {
        Path file = file(slug);
        try {
            Project p = GSON.fromJson(Files.readString(file), Project.class);
            if (p == null || p.slug() == null || p.answers() == null || p.artifacts() == null) {
                throw new IOException("file does not contain a project");
            }
            return p;
        } catch (IOException | RuntimeException e) {
            String why = e instanceof NoSuchFileException ? "no such file"
                    : String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString());   // Gson adds a help URL on line 2
            System.err.println("ProjectStore: cannot load " + file + ": " + why);
            return null;
        }
    }

    public void save(Project project) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(project.slug()), GSON.toJson(project));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save project to " + file(project.slug()), e);
        }
    }

    private Path file(String slug) { return dir.resolve(slug + ".json"); }
}
