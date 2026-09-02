package conductor.sdlc;

import conductor.agents.AgentClient;
import conductor.agents.AgentRequest;
import conductor.agents.AgentResponse;
import conductor.agents.ChatMessage;
import conductor.panel.Panel;

import java.util.List;

/**
 * What happens when a stage is completed. Most stages are pure Java over the
 * user's answers; REQUIREMENTS and DESIGN run the debate panel; PLAN, BUILD
 * and (optionally) VERIFY make exactly one lead call. Every agent failure is
 * folded into the artifact as readable text, so completing a stage can never
 * throw at the user.
 */
public final class StageRunner {

    private static final String REQ_Q = "Draft the requirements for this project as three lists: MUST have, "
            + "SHOULD have, must NEVER do. Be concrete and testable.";
    private static final String DESIGN_Q = "Propose a design: the main pieces, how they talk to each other, "
            + "the riskiest part, and what to build first.";
    private static final String PLAN_Q = "Break this project into ordered, sized tasks that an engineering agent "
            + "could execute one by one. Give each task a short id (T1, T2, ...), a title, a one-paragraph "
            + "description, a size (S, M or L) and the ids of the tasks it depends on.";
    private static final String BUILD_Q = "Write a build brief for an engineering agent. Include: goal, users, "
            + "requirements, design, ordered task list, definition of done, and what NOT to do. Assume the "
            + "reader has never spoken to the user.";
    private static final String VERIFY_Q = "Given these requirements and this plan, list up to 8 concrete "
            + "acceptance checks a non-engineer could run.";
    private static final String HANDOFF_SYSTEM = "The user message is a build brief prepared by Conductor on "
            + "behalf of a non-engineer. Carry it out, or reply with exactly what you need before you can start.";

    private final Panel panel;
    private final AgentClient lead;
    private final AgentClient openclaw;     // null when no gateway is configured
    private final int maxTokens;
    private final String orgContext;

    public StageRunner(Panel panel, AgentClient lead, AgentClient openclawOrNull, int maxTokens, String orgContext) {
        this.panel = panel;
        this.lead = lead;
        this.openclaw = openclawOrNull;
        this.maxTokens = maxTokens;
        this.orgContext = orgContext == null ? "" : orgContext.strip();
    }

    /** API calls {@link #complete} will make for this stage; shown to the user before they commit. */
    public int estimateCalls(Project p, Stage s) {
        return switch (s) {
            case REQUIREMENTS, DESIGN -> panel.apiCallCount();
            case PLAN, BUILD -> 1;
            case VERIFY -> p.answer("verify.how").isBlank() ? 0 : 1;
            default -> 0;
        };
    }

    /** Produces the stage artifact; the caller stores it. Never throws. */
    public String complete(Project p, Stage s, Panel.Listener listener) {
        String ctx = systemContext(p);
        return switch (s) {
            case IDEA, USERS -> summary(p, s);
            case REQUIREMENTS -> debate(ctx, REQ_Q, listener);
            case DESIGN -> debate(ctx, DESIGN_Q, listener);
            case PLAN -> plan(ctx, listener);
            case BUILD -> ask(ctx, BUILD_Q, listener);
            case VERIFY -> verify(p, ctx, listener);
            case SHIP -> releaseNotes(p);
        };
    }

    /** At most one lead call: sharpen the user's draft answer to an agentAssist question. */
    public String assist(Project p, Question q, String draftAnswer) {
        String draft = draftAnswer == null ? "" : draftAnswer.strip();
        String prompt = "The user is answering this question about their project:\n\n" + q.prompt()
                + "\n(" + q.help() + ")\n\nTheir draft answer:\n\n" + (draft.isBlank() ? "(nothing yet)" : draft)
                + "\n\nSharpen and expand it in the user's own voice: concrete, plain language, no jargon, "
                + "at most 120 words. Reply with the improved answer only.";
        AgentResponse r = send(lead, AgentRequest.text(systemContext(p), List.of(ChatMessage.user(prompt)), maxTokens));
        return r.ok() ? r.text().strip() : draft + "\n[assist unavailable: " + r.error() + "]";
    }

    /** Sends the BUILD artifact to the OpenClaw agent as one user message and returns its reply. */
    public String handOffToOpenClaw(Project p) {
        if (openclaw == null) return "OpenClaw is not configured (set openclaw.base.url in config.properties).";
        String brief = p.artifact(Stage.BUILD);
        if (brief.isBlank()) return "Complete the Build stage first - there is no build brief to hand off yet.";
        AgentResponse r = send(openclaw, AgentRequest.text(HANDOFF_SYSTEM, List.of(ChatMessage.user(brief)), maxTokens));
        return r.ok() ? r.text() : "[openclaw unavailable: " + r.error() + "]";
    }

    // ---- stages ---------------------------------------------------------------------------------

    private static String summary(Project p, Stage s) {
        String answers = ProjectContext.renderStage(p, s);
        return "# " + s.title() + "\n\n" + (answers.isBlank() ? "_No answers yet._\n" : answers);
    }

    private String debate(String ctx, String question, Panel.Listener listener) {
        try {
            return panel.debate(ctx, question, listener);
        } catch (RuntimeException e) {
            return "[panel unavailable: " + e + "]";
        }
    }

    private String plan(String ctx, Panel.Listener listener) {
        status(listener, "Asking " + lead.modelName() + " for a task plan...");
        AgentResponse r = send(lead, AgentRequest.json(ctx, List.of(ChatMessage.user(PLAN_Q)), PlanFormat.SCHEMA, maxTokens));
        return r.ok() ? PlanFormat.artifact(r.text()) : "[lead unavailable: " + r.error() + "]";
    }

    private String verify(Project p, String ctx, Panel.Listener listener) {
        StringBuilder sb = new StringBuilder("# Verify\n\n## Checklist\n");
        List<String> titles = PlanFormat.titles(p.artifact(Stage.PLAN));
        for (String t : titles) sb.append("- [ ] ").append(t).append(" - works as described\n");
        if (titles.isEmpty()) sb.append("- [ ] (No task list found: complete the Plan stage to get one check per task.)\n");
        String answers = ProjectContext.renderStage(p, Stage.VERIFY);
        if (!answers.isBlank()) sb.append("\n## How we will check\n\n").append(answers);
        if (!p.answer("verify.how").isBlank()) {
            sb.append("\n## Suggested checks\n\n").append(ask(ctx, VERIFY_Q, listener)).append('\n');
        }
        return sb.toString();
    }

    private static String releaseNotes(Project p) {
        return "# Release notes: " + p.name() + "\n\n"
                + section("What it is", p.answer("idea.what"))
                + section("Who it is for", p.answer("users.who"))
                + section("Announcement", p.answer("ship.message"))
                + section("How to get it", p.answer("ship.channel"))
                + section("Who hears first", p.answer("ship.audience"))
                + section("What we learned", p.answer("ship.learned"));
    }

    private static String section(String title, String body) {
        return "## " + title + "\n\n" + (body.isBlank() ? "_(not answered)_" : body.strip()) + "\n\n";
    }

    // ---- agent plumbing -------------------------------------------------------------------------

    private String systemContext(Project p) {
        return (orgContext.isBlank() ? "" : orgContext + "\n\n") + ProjectContext.render(p) + "\n";
    }

    private String ask(String ctx, String prompt, Panel.Listener listener) {
        status(listener, "Asking " + lead.modelName() + "...");
        AgentResponse r = send(lead, AgentRequest.text(ctx, List.of(ChatMessage.user(prompt)), maxTokens));
        return r.ok() ? r.text() : "[lead unavailable: " + r.error() + "]";
    }

    /** Clients promise not to throw, but a bug in one must still not take the stage down. */
    private static AgentResponse send(AgentClient client, AgentRequest request) {
        try {
            return client.send(request);
        } catch (RuntimeException e) {
            return AgentResponse.error(e.toString());
        }
    }

    private static void status(Panel.Listener listener, String text) {
        if (listener != null) listener.on("status", "lead", text);
    }
}
