package conductor.sdlc;

import java.util.List;
import java.util.Optional;

import static conductor.sdlc.Stage.*;

/**
 * Every question Conductor asks, in the order asked. Pure data, phrased for
 * someone who has never written software. Exactly one question per stage
 * carries agentAssist: that is the single place per stage where a second
 * opinion is worth the cost and latency of an API call.
 */
public final class Questions {

    private static final List<Question> ALL = List.of(
            Question.of("idea.what", IDEA, "What do you want to build?",
                    "One or two sentences, the way you would describe it to a friend."),
            Question.of("idea.why", IDEA, "Why does it matter? What problem does it solve?",
                    "Describe the pain or the opportunity. An agent can help sharpen this into a crisp problem statement.")
                    .withAgentAssist(),
            Question.optional("idea.today", IDEA, "How do people deal with this today?",
                    "Spreadsheets, paper, another app, nothing at all - whatever they do now."),
            Question.optional("idea.not", IDEA, "What is this NOT going to do?",
                    "Saying no early keeps the project small enough to finish."),

            Question.of("users.who", USERS, "Who will use this?",
                    "Name the kinds of people, e.g. 'volunteers at my food bank' or 'my two co-founders'."),
            Question.of("users.need", USERS, "What do they need to get done with it?",
                    "Describe the job in their words, not as a list of features."),
            Question.of("users.success", USERS, "What does success look like?",
                    "How would you know in three months that it worked? An agent can turn this into measurable criteria.")
                    .withAgentAssist(),
            Question.optional("users.context", USERS, "Where and when will they use it?",
                    "On a phone in the field, at a desk once a week, in a hurry, with bad internet..."),

            Question.of("req.must", REQUIREMENTS, "What must it absolutely do?",
                    "The things without which it is useless. An agent can make these concrete and testable.")
                    .withAgentAssist(),
            Question.optional("req.should", REQUIREMENTS, "What would be nice but not essential?",
                    "Things the first version could live without."),
            Question.of("req.never", REQUIREMENTS, "What must it never do?",
                    "Lose data, share private information, spend money without asking..."),
            Question.optional("req.constraints", REQUIREMENTS, "Any hard constraints?",
                    "Budget, deadline, must work offline, must use an existing login system..."),

            Question.of("design.pieces", DESIGN, "What are the main pieces, as far as you can tell?",
                    "A screen people type into, a place data lives, a daily email... An agent can propose a fuller picture.")
                    .withAgentAssist(),
            Question.of("design.data", DESIGN, "What information does it need to remember?",
                    "People, items, dates, money, photos - whatever it must keep track of."),
            Question.optional("design.connect", DESIGN, "What existing tools or services should it connect to?",
                    "Google Sheets, your email, a payment provider, or nothing."),
            Question.optional("design.worry", DESIGN, "Which part worries you most?",
                    "Naming the riskiest part lets the panel focus on it."),

            Question.of("plan.first", PLAN, "What is the smallest version that would already be useful?",
                    "An agent can help trim this to a true first slice.")
                    .withAgentAssist(),
            Question.of("plan.deadline", PLAN, "When do you need it, and what happens if it is late?",
                    "A date plus the consequence tells us how hard the deadline really is."),
            Question.optional("plan.people", PLAN, "Who is available to build and test it?",
                    "You, an AI agent, a friend who codes, a contractor..."),
            Question.optional("plan.risks", PLAN, "What could derail the project?",
                    "Unknowns, waiting on other people, holidays, budget running out..."),

            Question.of("build.target", BUILD, "Who or what will build it?",
                    "An AI engineering agent (OpenClaw, Claude Code), a developer you know, yourself..."),
            Question.optional("build.tech", BUILD, "Any technology preferences or constraints?",
                    "'Must run in a web browser', 'we already pay for Google Workspace', or 'no preference'."),
            Question.of("build.done", BUILD, "How will you know the build is finished?",
                    "An agent can turn this into a checklist-style definition of done.")
                    .withAgentAssist(),
            Question.optional("build.avoid", BUILD, "Anything the builder must NOT do?",
                    "Do not touch the live data, do not spend money, do not change the logo..."),

            Question.of("verify.how", VERIFY, "How will you check it does what you asked?",
                    "Try it yourself, ask two users, compare against the old spreadsheet... An agent can suggest concrete checks.")
                    .withAgentAssist(),
            Question.optional("verify.who", VERIFY, "Who will try it before it goes live?",
                    "Names or roles - the people whose opinion decides."),
            Question.of("verify.reject", VERIFY, "What would make you send it back?",
                    "The failures you would not accept, even under time pressure."),

            Question.of("ship.audience", SHIP, "Who hears about it first?",
                    "The first group of people who get access or an announcement."),
            Question.of("ship.channel", SHIP, "How do they get it?",
                    "A link, an email, an app store, installed by you on their machine..."),
            Question.of("ship.message", SHIP, "What is the one-paragraph announcement?",
                    "Plain words: what it does and why they should care. An agent can polish it.")
                    .withAgentAssist(),
            Question.optional("ship.learned", SHIP, "What did you learn building this?",
                    "Captured now while it is fresh; it makes the next project easier.")
    );

    private Questions() {}

    public static List<Question> all() { return ALL; }

    public static List<Question> forStage(Stage stage) {
        return ALL.stream().filter(q -> q.stage() == stage).toList();
    }

    public static Optional<Question> byId(String id) {
        return ALL.stream().filter(q -> q.id().equals(id)).findFirst();
    }
}
