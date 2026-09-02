package conductor.sdlc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trips a project through JSON on disk and checks bad files cannot hurt us. */
class ProjectStoreTest {

    @TempDir Path dir;

    @Test
    void roundTripKeepsAnswersArtifactsAndStage() {
        ProjectStore store = new ProjectStore(dir.resolve("projects"));   // does not exist yet: created on save
        Project p = new Project("My Cool App!");
        p.setAnswer("idea.what", "A garden tracker\nwith \"quotes\", <tags> & unicode: ü");
        p.setArtifact(Stage.IDEA, "# Idea\n\nsummary");
        p.setCurrent(Stage.REQUIREMENTS);
        store.save(p);

        assertTrue(store.exists("my-cool-app"));
        assertEquals(List.of("my-cool-app"), store.listSlugs());
        Project back = store.load("my-cool-app");
        assertNotNull(back);
        assertEquals("My Cool App!", back.name());
        assertEquals("my-cool-app", back.slug());
        assertEquals(p.answer("idea.what"), back.answer("idea.what"));
        assertEquals("# Idea\n\nsummary", back.artifact(Stage.IDEA));
        assertTrue(back.isComplete(Stage.IDEA));
        assertFalse(back.isComplete(Stage.USERS));
        assertEquals(Stage.REQUIREMENTS, back.current());
        assertEquals(p.createdAt(), back.createdAt());
    }

    @Test
    void saveOverwritesAndListSortsSlugs() {
        ProjectStore store = new ProjectStore(dir);
        Project p = new Project("Zeta");
        store.save(p);
        p.setAnswer("idea.what", "second save");
        store.save(p);
        store.save(new Project("Alpha"));
        assertEquals(List.of("alpha", "zeta"), store.listSlugs());
        assertEquals("second save", store.load("zeta").answer("idea.what"));
    }

    @Test
    void missingDirOrFileIsNotAnError() {
        ProjectStore store = new ProjectStore(dir.resolve("nowhere"));
        assertEquals(List.of(), store.listSlugs());
        assertFalse(store.exists("ghost"));
        assertNull(store.load("ghost"));
    }

    @Test
    void corruptFilesLoadAsNull() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Files.writeString(dir.resolve("bad.json"), "{ this is not json");
        Files.writeString(dir.resolve("empty.json"), "");
        Files.writeString(dir.resolve("wrong-shape.json"), "[1, 2, 3]");
        assertNull(store.load("bad"));
        assertNull(store.load("empty"));
        assertNull(store.load("wrong-shape"));
        assertEquals(List.of("bad", "empty", "wrong-shape"), store.listSlugs());   // still listed; the user can delete them
    }
}
