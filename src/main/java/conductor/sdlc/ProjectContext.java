package conductor.sdlc;

/**
 * Renders everything known about a project as one Markdown block. This is
 * the system context every agent call receives, so agents see the same whole
 * picture the user does. It contains nothing that changes between calls at
 * the same stage, which lets providers cache it as a stable prefix.
 */
public final class ProjectContext {

    private ProjectContext() {}

    public static String render(Project p) {
        StringBuilder sb = new StringBuilder("# Project: ").append(p.name()).append("\n\n");
        for (Stage s : Stage.values()) {
            String answers = renderStage(p, s);
            String artifact = p.artifact(s);
            if (answers.isBlank() && artifact.isBlank()) continue;
            sb.append("## ").append(s.title()).append("\n\n");
            if (!answers.isBlank()) sb.append(answers).append('\n');
            if (!artifact.isBlank()) {
                sb.append("### ").append(s.title()).append(" - agreed output\n\n")
                        .append(artifact.strip()).append("\n\n");
            }
        }
        return sb.toString().stripTrailing() + "\n";
    }

    /** Only this stage's answered questions as "**prompt**" / answer pairs; empty when nothing is answered. */
    public static String renderStage(Project p, Stage s) {
        StringBuilder sb = new StringBuilder();
        for (Question q : Questions.forStage(s)) {
            String answer = p.answer(q.id());
            if (answer.isBlank()) continue;
            sb.append("**").append(q.prompt()).append("**\n").append(answer.strip()).append("\n\n");
        }
        return sb.toString();
    }
}
