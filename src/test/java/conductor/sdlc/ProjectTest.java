package conductor.sdlc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Slugs become file names, so the edge cases matter more than the happy path. */
class ProjectTest {

    @Test
    void slugifyCases() {
        assertEquals("my-cool-app", Project.slugify("My Cool App!"));
        assertEquals("hello-world", Project.slugify("  Hello   World  "));
        assertEquals("x", Project.slugify("--x--"));
        assertEquals("v2-0", Project.slugify("v2.0"));
        assertEquals("ber-app-2", Project.slugify("ÜBER app 2"));
        assertEquals("project", Project.slugify(""));
        assertEquals("project", Project.slugify("   "));
        assertEquals("project", Project.slugify("!!!"));
        assertEquals("project", Project.slugify(null));
    }

    @Test
    void newProjectStartsAtIdeaWithNothingComplete() {
        Project p = new Project("Garden Buddy");
        assertEquals("garden-buddy", p.slug());
        assertEquals(Stage.IDEA, p.current());
        assertNotNull(p.createdAt());
        assertEquals(p.createdAt(), p.updatedAt());
        for (Stage s : Stage.values()) assertFalse(p.isComplete(s));
    }

    @Test
    void answersAndArtifactsDefaultToEmptyAndNullBecomesEmpty() {
        Project p = new Project("x");
        assertEquals("", p.answer("idea.what"));
        assertEquals("", p.artifact(Stage.PLAN));
        p.setAnswer("idea.what", null);
        p.setArtifact(Stage.PLAN, null);
        assertEquals("", p.answer("idea.what"));
        assertFalse(p.isComplete(Stage.PLAN));
        p.setArtifact(Stage.PLAN, "  done  ");
        assertTrue(p.isComplete(Stage.PLAN));
    }
}
