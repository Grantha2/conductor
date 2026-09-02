package conductor.sdlc;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The question bank is data; these tests keep it honest about its own rules. */
class QuestionsTest {

    @Test
    void everyStageHasThreeToFiveQuestions() {
        for (Stage s : Stage.values()) {
            int n = Questions.forStage(s).size();
            assertTrue(n >= 3 && n <= 5, s + " has " + n + " questions");
        }
    }

    @Test
    void idsAreUniqueLowercaseAndTextIsFilledIn() {
        Set<String> ids = new HashSet<>();
        for (Question q : Questions.all()) {
            assertTrue(ids.add(q.id()), "duplicate id " + q.id());
            assertTrue(q.id().matches("[a-z]+\\.[a-z]+"), "id style: " + q.id());
            assertFalse(q.prompt().isBlank(), q.id() + " prompt");
            assertFalse(q.help().isBlank(), q.id() + " help");
        }
        assertEquals(Questions.all().size(), ids.size());
    }

    @Test
    void exactlyOneAgentAssistPerStage() {
        for (Stage s : Stage.values()) {
            long n = Questions.forStage(s).stream().filter(Question::agentAssist).count();
            assertEquals(1, n, s + " should have exactly one agentAssist question");
        }
    }

    @Test
    void forStageReturnsOnlyThatStageInOrder() {
        for (Stage s : Stage.values()) {
            for (Question q : Questions.forStage(s)) assertEquals(s, q.stage());
        }
        assertEquals("idea.what", Questions.forStage(Stage.IDEA).get(0).id());
    }

    @Test
    void byIdFindsKnownAndRejectsUnknown() {
        Question q = Questions.byId("users.success").orElseThrow();
        assertEquals(Stage.USERS, q.stage());
        assertTrue(q.agentAssist());
        assertTrue(Questions.byId("nope.nothing").isEmpty());
    }
}
