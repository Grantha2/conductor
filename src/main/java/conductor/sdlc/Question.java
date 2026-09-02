package conductor.sdlc;

/**
 * One thing we ask the user at a stage. Plain Java asks it; the answer is
 * stored on the {@link Project} under {@code id}.
 *
 * <p>{@code agentAssist} is the one knob that decides whether an agent gets
 * involved: when true, after the user answers, StageRunner may make AT MOST
 * ONE agent call to sharpen or expand that answer (and shows the result to
 * the user for approval). When false, no agent is called for this question.
 * The default should be false — agents cost money and add latency; only
 * turn this on where a second opinion clearly earns its keep.
 */
public record Question(
        String id,          // stable key, e.g. "idea.summary"
        Stage stage,
        String prompt,      // what the user sees, phrased as a question
        String help,        // one or two sentences of guidance, may be ""
        boolean required,
        boolean agentAssist
) {
    public static Question of(String id, Stage stage, String prompt, String help) {
        return new Question(id, stage, prompt, help, true, false);
    }

    public static Question optional(String id, Stage stage, String prompt, String help) {
        return new Question(id, stage, prompt, help, false, false);
    }

    public Question withAgentAssist() {
        return new Question(id, stage, prompt, help, required, true);
    }
}
