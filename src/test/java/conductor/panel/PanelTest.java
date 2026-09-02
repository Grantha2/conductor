package conductor.panel;

import conductor.agents.AgentClient;
import conductor.agents.FakeAgentClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelTest {

    private final FakeAgentClient a = new FakeAgentClient("A");
    private final FakeAgentClient b = new FakeAgentClient("B");
    private final FakeAgentClient c = new FakeAgentClient("C");
    private final List<Panelist> panelists = List.of(
            new Panelist("Alpha", "arch", "lens a"),
            new Panelist("Beta", "ideas", "lens b"),
            new Panelist("Gamma", "delivery", "lens c"));

    @Test
    void threePanelistsOneRoundMakesSevenCalls() {
        a.reply("A1").reply("A2").reply("SYNTH");
        var panel = new Panel(List.<AgentClient>of(a, b, c), panelists, 1, 100);
        var kinds = new ArrayList<String>();
        var whos = new ArrayList<String>();

        String synthesis = panel.debate("ctx\n", "Should we ship?", (kind, who, text) -> {
            if (!kind.equals("status")) { kinds.add(kind); whos.add(who); }
        });

        assertEquals("SYNTH", synthesis);
        assertEquals(7, panel.apiCallCount());
        assertEquals(7, a.requests.size() + b.requests.size() + c.requests.size());
        assertEquals(3, a.requests.size());
        assertEquals(2, b.requests.size());

        assertEquals(List.of("phase1", "phase1", "phase1", "phase2", "phase2", "phase2", "synthesis"), kinds);
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Alpha", "Beta", "Gamma", "Alpha"), whos);

        String systemA = a.requests.get(0).system();
        assertTrue(systemA.startsWith("ctx\n=== YOUR AGENT IDENTITY ===\nAgent name: Alpha"));

        String phase2ForB = b.requests.get(1).messages().get(0).content();
        assertTrue(phase2ForB.contains("Alpha") && phase2ForB.contains("Gamma"));
        assertFalse(phase2ForB.contains("Beta"));
        assertTrue(phase2ForB.contains("A1"), "sees Alpha's phase-1 answer");
        assertTrue(phase2ForB.contains("C #1"), "sees Gamma's phase-1 answer");

        String synthPrompt = a.requests.get(2).messages().get(0).content();
        assertTrue(synthPrompt.contains("A1") && synthPrompt.contains("A2") && synthPrompt.contains("B #2"));
    }

    @Test
    void failedPanelistBecomesPlaceholderAndDebateContinues() {
        b.replyError("[openai HTTP 500] down");
        var panel = new Panel(List.<AgentClient>of(a, b, c), panelists, 1, 100);
        var phase1 = new ArrayList<String>();
        panel.debate(null, "q", (kind, who, text) -> { if (kind.equals("phase1")) phase1.add(text); });
        assertEquals("[Beta unavailable: [openai HTTP 500] down]", phase1.get(1));
        assertTrue(a.requests.get(1).messages().get(0).content().contains("Beta unavailable"));
    }

    @Test
    void twoPanelistsZeroRoundsMakesThreeCallsAndNeverPassesNullToTheListener() {
        var panel = new Panel(List.<AgentClient>of(a, b), panelists.subList(0, 2), 0, 100);
        assertEquals(3, panel.apiCallCount());
        var kinds = new ArrayList<String>();
        panel.debate(null, "q", (kind, who, text) -> {
            assertNotNull(kind);
            assertNotNull(who);
            assertNotNull(text);
            kinds.add(kind);
        });
        assertEquals(3, a.requests.size() + b.requests.size());
        assertFalse(kinds.contains("phase2"));
        assertTrue(kinds.contains("synthesis"));
        String synth = a.requests.get(1).messages().get(0).content();
        assertFalse(synth.contains("Final positions"), "no rounds -> no final-positions section");
        assertTrue(synth.contains("B #1"), "synthesis quotes the other seat's phase-1 answer");
        assertEquals(a.requests.get(0).system(), a.requests.get(1).system(), "byte-identical system prefix so the cache hits");
    }

    @Test
    void rejectsMismatchedOrTooSmallPanels() {
        assertThrows(IllegalArgumentException.class, () -> new Panel(List.of(a, b), panelists, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new Panel(List.of(a), panelists.subList(0, 1), 1, 100));
    }
}
