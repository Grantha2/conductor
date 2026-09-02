package conductor.sdlc;

/**
 * The rails. A project moves through these stages in order; the UI shows
 * them as a left-hand track and the current stage's questions in the centre.
 *
 * <p>Each stage has a plain-language title and a one-line goal that is shown
 * to the user. The questions asked at each stage live in {@link Questions};
 * what happens when a stage is completed (which agent call, if any) lives in
 * {@link StageRunner}.
 */
public enum Stage {
    IDEA        ("Idea",          "Say what you want to build and why it matters."),
    USERS       ("Users & Goals", "Who is this for? What does success look like for them?"),
    REQUIREMENTS("Requirements",  "What must it do? What must it never do?"),
    DESIGN      ("Design",        "How will it be built? What are the main pieces?"),
    PLAN        ("Plan",          "Break the work into ordered, sized tasks."),
    BUILD       ("Build",         "Hand a precise brief to an engineering agent or team."),
    VERIFY      ("Verify",        "Does it do what we said? How do we know?"),
    SHIP        ("Ship",          "Release it, tell people, and capture what you learned.");

    private final String title;
    private final String goal;

    Stage(String title, String goal) {
        this.title = title;
        this.goal = goal;
    }

    public String title() { return title; }
    public String goal()  { return goal; }

    public boolean isFirst() { return ordinal() == 0; }
    public boolean isLast()  { return ordinal() == values().length - 1; }

    public Stage next()     { return isLast()  ? this : values()[ordinal() + 1]; }
    public Stage previous() { return isFirst() ? this : values()[ordinal() - 1]; }
}
