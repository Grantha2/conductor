package conductor.sdlc;

import conductor.panel.Panel;
import conductor.panel.Panelist;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stage semantics with scripted clients: which stages call whom, how often, and how failures read. */
class StageRunnerTest {

    private static final String PLAN_JSON = """
            {"tasks":[
              {"id":"T1","title":"Set up the database","description":"Create tables for plants and waterings.","size":"S"},
              {"id":"T2","title":"Build the plant list screen","description":"Show every plant with its last watering.","size":"M","dependsOn":["T1"]},
              {"id":"T3","title":"Daily reminder email","description":"Send one email per morning.","size":"L","dependsOn":["T1","T2"]}
            ]}""";

    private static Project project() {
        Project p = new Project("Garden Buddy");
        p.setAnswer("idea.what", "An app that reminds me to water my plants");
        p.setAnswer("idea.why", "My basil keeps dying");
        return p;
    }

    private static Panel panel(FakeClient a, FakeClient b) {
        return new Panel(List.of(a, b), List.of(new Panelist("A", "pa", "la"), new Panelist("B", "pb", "lb")), 1, 500);
    }

    private static StageRunner runner(FakeClient lead, FakeClient other) {
        return new StageRunner(panel(lead, other), lead, null, 500, "");
    }

    @Test
    void ideaIsPureJavaMarkdownWithNoCalls() {
        FakeClient lead = new FakeClient("lead", "unused");
        FakeClient other = new FakeClient("other", "unused");
        String art = runner(lead, other).complete(project(), Stage.IDEA, null);
        assertTrue(art.startsWith("# Idea"));
        assertTrue(art.contains("An app that reminds me to water my plants"));
        assertTrue(art.contains("My basil keeps dying"));
        assertEquals(0, lead.sent.size() + other.sent.size());
    }

    @Test
    void planRendersChecklistAndEmbedsRawJson() {
        FakeClient lead = new FakeClient("lead", PLAN_JSON);
        FakeClient other = new FakeClient("other", "unused");
        StageRunner r = runner(lead, other);
        String art = r.complete(project(), Stage.PLAN, null);

        assertEquals(1, lead.sent.size());
        assertEquals(0, other.sent.size());
        assertTrue(lead.sent.get(0).wantsJson());
        assertTrue(art.contains("## Small tasks") && art.contains("## Medium tasks") && art.contains("## Large tasks"));
        assertTrue(art.contains("- [ ] **T2** Build the plant list screen _(after T1)_"));
        assertTrue(art.contains("_(after T1, T2)_"));
        assertTrue(art.endsWith("```json\n" + PLAN_JSON + "\n```"));
        assertEquals(List.of("Set up the database", "Build the plant list screen", "Daily reminder email"), PlanFormat.titles(art));
        assertEquals(1, r.estimateCalls(project(), Stage.PLAN));
    }

    @Test
    void planFallsBackWhenLeadReturnsGarbage() {
        String garbage = "Sure! Here are some tasks: 1) do stuff 2) do more stuff";
        FakeClient lead = new FakeClient("lead", garbage);
        String art = runner(lead, new FakeClient("other")).complete(project(), Stage.PLAN, null);
        assertTrue(art.startsWith("# Plan (could not parse as JSON)"));
        assertTrue(art.contains(garbage));
        assertEquals(List.of(), PlanFormat.titles(art));
        assertEquals(List.of(), PlanFormat.titles(""));
    }

    @Test
    void requirementsRunsTheDebateExactlyOnce() {
        FakeClient lead = new FakeClient("lead", "lead says");
        FakeClient other = new FakeClient("other", "other says");
        Panel panel = panel(lead, other);
        List<String> kinds = new ArrayList<>();
        StageRunner r = new StageRunner(panel, lead, null, 500, "Org: Acme Gardens");
        String art = r.complete(project(), Stage.REQUIREMENTS, (kind, who, text) -> kinds.add(kind));

        assertEquals(5, lead.sent.size() + other.sent.size());          // 2 openers + 2 reactions + 1 synthesis
        assertEquals(panel.apiCallCount(), lead.sent.size() + other.sent.size());
        assertEquals(panel.apiCallCount(), r.estimateCalls(project(), Stage.REQUIREMENTS));
        assertEquals("lead says", art);                                 // synthesis is written by client 0
        assertTrue(kinds.contains("phase1") && kinds.contains("phase2") && kinds.contains("synthesis"));
        String system = lead.sent.get(0).system();
        assertTrue(system.startsWith("Org: Acme Gardens"));
        assertTrue(system.contains("# Project: Garden Buddy") && system.contains("My basil keeps dying"));
        assertTrue(lead.sent.get(0).messages().get(0).content().contains("MUST have"));
    }

    @Test
    void leadFailureBecomesReadableTextNotAnException() {
        FakeClient lead = FakeClient.failing("lead", "HTTP 500 from provider");
        StageRunner r = runner(lead, new FakeClient("other"));
        assertEquals("[lead unavailable: HTTP 500 from provider]", r.complete(project(), Stage.BUILD, null));
        Question q = Questions.byId("idea.why").orElseThrow();
        assertEquals("my draft\n[assist unavailable: HTTP 500 from provider]", r.assist(project(), q, "my draft"));
    }

    @Test
    void verifyCallsLeadOnlyWhenTheUserSaidHowToCheck() {
        Project p = project();
        p.setArtifact(Stage.PLAN, PlanFormat.artifact(PLAN_JSON));
        FakeClient lead = new FakeClient("lead", "1. Open the app and add a plant.");
        StageRunner r = runner(lead, new FakeClient("other"));

        assertEquals(0, r.estimateCalls(p, Stage.VERIFY));
        String art = r.complete(p, Stage.VERIFY, null);
        assertEquals(0, lead.sent.size());
        assertTrue(art.contains("- [ ] Set up the database"));
        assertFalse(art.contains("Suggested checks"));

        p.setAnswer("verify.how", "I will try it on my phone for a week");
        assertEquals(1, r.estimateCalls(p, Stage.VERIFY));
        art = r.complete(p, Stage.VERIFY, null);
        assertEquals(1, lead.sent.size());
        assertTrue(art.contains("## Suggested checks"));
        assertTrue(art.contains("1. Open the app and add a plant."));
    }

    @Test
    void shipIsATemplateOverAnswers() {
        Project p = project();
        p.setAnswer("ship.message", "Garden Buddy is here!");
        String art = runner(new FakeClient("lead"), new FakeClient("other")).complete(p, Stage.SHIP, null);
        assertTrue(art.startsWith("# Release notes: Garden Buddy"));
        assertTrue(art.contains("Garden Buddy is here!"));
        assertTrue(art.contains("_(not answered)_"));
    }

    @Test
    void handOffGoesToOpenClawOnlyWhenConfigured() {
        FakeClient lead = new FakeClient("lead");
        FakeClient other = new FakeClient("other");
        Project p = project();
        assertTrue(runner(lead, other).handOffToOpenClaw(p).contains("not configured"));

        FakeClient claw = new FakeClient("openclaw", "On it.");
        StageRunner r = new StageRunner(panel(lead, other), lead, claw, 500, "");
        assertTrue(r.handOffToOpenClaw(p).contains("Complete the Build stage first"));
        assertEquals(0, claw.sent.size());
        p.setArtifact(Stage.BUILD, "the brief");
        assertEquals("On it.", r.handOffToOpenClaw(p));
        assertEquals("the brief", claw.sent.get(0).messages().get(0).content());
    }
}
