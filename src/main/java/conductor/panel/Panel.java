package conductor.panel;

import conductor.agents.AgentClient;
import conductor.agents.AgentRequest;
import conductor.agents.ChatMessage;

import java.util.List;

/**
 * The three-phase debate: every panelist answers alone, then reacts to the
 * others for one or more rounds, then the lead (index 0) synthesises. Each
 * call is stateless and carries its own system prompt, so a failed panelist
 * degrades to a placeholder line instead of aborting the stage.
 */
public final class Panel {

    /** kind is one of "status", "phase1", "phase2", "synthesis". */
    public interface Listener { void on(String kind, String who, String text); }

    private final List<AgentClient> clients;
    private final List<Panelist> panelists;
    private final int rounds;
    private final int maxTokens;

    public Panel(List<AgentClient> clients, List<Panelist> panelists, int rounds, int maxTokens) {
        if (clients == null || panelists == null || clients.size() != panelists.size() || clients.size() < 2) {
            throw new IllegalArgumentException("Panel needs >= 2 clients with one panelist each");
        }
        if (rounds < 0) throw new IllegalArgumentException("rounds must be >= 0");
        this.clients = List.copyOf(clients);
        this.panelists = List.copyOf(panelists);
        this.rounds = rounds;
        this.maxTokens = maxTokens;
    }

    /** Runs the full debate and returns the lead's synthesis. */
    public String debate(String systemContext, String question, Listener listener) {
        int n = clients.size();
        String context = systemContext == null ? "" : systemContext;
        var phase1 = new String[n];
        var latest = new String[n];

        for (int i = 0; i < n; i++) {
            fire(listener, "status", name(i), "Asking " + name(i) + " (" + panelists.get(i).perspective() + ")...");
            phase1[i] = latest[i] = ask(i, context, "Question:\n" + question
                    + "\n\nAnswer from your assigned perspective. Be concrete and specific.");
            fire(listener, "phase1", name(i), phase1[i]);
        }

        for (int round = 1; round <= rounds; round++) {
            var reactions = new String[n];
            for (int i = 0; i < n; i++) {
                fire(listener, "status", name(i), "Round " + round + ": " + name(i) + " reacting...");
                var prompt = new StringBuilder("The question was:\n").append(question)
                        .append("\n\nYour fellow panelists said:\n");
                for (int j = 0; j < n; j++) {
                    if (j != i) prompt.append("\n--- ").append(name(j)).append(" ---\n").append(latest[j]).append('\n');
                }
                prompt.append("\nReact from your own perspective: where do you agree, where do you disagree "
                        + "and why, and what did they miss? Update your position if you were persuaded.");
                reactions[i] = ask(i, context, prompt.toString());
                fire(listener, "phase2", name(i), reactions[i]);
            }
            latest = reactions;
        }

        fire(listener, "status", name(0), "Synthesising...");
        var prompt = new StringBuilder("The question was:\n").append(question).append("\n\nInitial answers:\n");
        for (int i = 0; i < n; i++) prompt.append("\n--- ").append(name(i)).append(" ---\n").append(phase1[i]).append('\n');
        if (rounds > 0) {
            prompt.append("\nFinal positions after debate:\n");
            for (int i = 0; i < n; i++) prompt.append("\n--- ").append(name(i)).append(" ---\n").append(latest[i]).append('\n');
        }
        prompt.append("\nWrite the panel's synthesis for a non-engineer, with these sections: "
                + "Agreements; Disagreements (and what would settle them); Insights someone missed; "
                + "Concrete recommendation (what to do next, in order).");
        String synthesis = ask(0, context, prompt.toString());
        fire(listener, "synthesis", name(0), synthesis);
        fire(listener, "status", name(0), "Debate complete.");
        return synthesis;
    }

    /** 1 synthesis + n phase-1 answers + n reactions per round. */
    public int apiCallCount() {
        return 1 + clients.size() + clients.size() * rounds;
    }

    private String ask(int i, String context, String userText) {
        var request = AgentRequest.text(context + panelists.get(i).briefing(),
                List.of(ChatMessage.user(userText)), maxTokens);
        var response = clients.get(i).send(request);
        return response.ok() ? response.text() : "[" + name(i) + " unavailable: " + response.error() + "]";
    }

    private String name(int i) { return panelists.get(i).name(); }

    private static void fire(Listener listener, String kind, String who, String text) {
        if (listener != null) listener.on(kind, who, text);
    }
}
