package conductor;

import conductor.config.Config;
import conductor.sdlc.Project;
import conductor.sdlc.ProjectStore;
import conductor.sdlc.Question;
import conductor.sdlc.Questions;
import conductor.sdlc.Stage;
import conductor.sdlc.StageRunner;
import conductor.ui.MainGui;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Entry point. GUI by default; {@code --cli} walks the same rails in a
 * terminal, which is handy over SSH and for smoke-testing the agent wiring
 * without a display. HiDPI properties are set before any Swing class loads
 * because the toolkit reads them exactly once, at start-up.
 */
public final class Main {

    public static void main(String[] args) {
        for (String key : List.of("sun.java2d.uiScale.enabled", "sun.java2d.dpiaware", "awt.useSystemAAFontSettings", "swing.aatext")) {
            if (System.getProperty(key) == null) System.setProperty(key, "true");
        }
        if (Arrays.asList(args).contains("--cli")) cli(); else MainGui.launch();
    }

    private static void cli() {
        Config config;
        try {
            config = new Config(Config.defaultPath());
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        if (!config.hasAllKeys()) {
            System.err.println("config.properties is missing one or more API keys (anthropic.key, openai.key, gemini.key).");
            System.exit(1);
        }
        Wiring wiring = new Wiring(config);
        StageRunner runner = wiring.runner(wiring.loadPanelists(), Wiring.loadContext());
        ProjectStore store = new ProjectStore(Wiring.PROJECTS_DIR);
        Scanner in = new Scanner(System.in);
        Project p = pickProject(store, in);

        for (Stage s = p.current(); ; s = s.next()) {
            System.out.println("\n=== " + s.title() + " ===\n" + s.goal());
            for (Question q : Questions.forStage(s)) askQuestion(p, q, runner, in);
            store.save(p);
            int calls = runner.estimateCalls(p, s);
            if (!yes(in, "\nComplete stage \"" + s.title() + "\"? This will make " + calls + " API call(s). (y/n) ", "y")) break;
            String artifact = runner.complete(p, s, (kind, who, text) -> System.out.println("  [" + kind + "] " + who + ": " + firstLine(text)));
            System.out.println("\n" + artifact);
            p.setArtifact(s, artifact);
            if (s.ordinal() >= p.current().ordinal()) p.setCurrent(s.next());
            store.save(p);
            if (s.isLast()) { System.out.println("\nAll stages complete. Saved under " + store.dir() + "."); break; }
        }
    }

    private static Project pickProject(ProjectStore store, Scanner in) {
        List<String> slugs = store.listSlugs();
        if (!slugs.isEmpty()) {
            System.out.println("Saved projects: " + String.join(", ", slugs));
            System.out.print("Type one to open it, or press Enter for a new project: ");
            String slug = in.nextLine().strip();
            if (!slug.isEmpty()) {
                Project p = store.load(slug);
                if (p != null) return p;
                System.out.println("Could not open '" + slug + "'; starting a new project.");
            }
        }
        System.out.print("Project name: ");
        String name = in.nextLine().strip();
        return new Project(name.isEmpty() ? "Untitled project" : name);
    }

    private static void askQuestion(Project p, Question q, StageRunner runner, Scanner in) {
        System.out.println("\n" + q.prompt() + (q.required() ? " *" : "") + "\n  (" + q.help() + ")");
        String current = p.answer(q.id());
        if (!current.isBlank()) System.out.println("  Current answer: " + current.replace("\n", "\n  ") + "\n  Type a new one, or just press Enter to keep it.");
        System.out.println("  Finish with an empty line.");
        String answer = readBlock(in);
        if (!answer.isBlank()) p.setAnswer(q.id(), answer);
        if (q.agentAssist() && !p.answer(q.id()).isBlank() && yes(in, "  Ask an agent to sharpen this? 1 API call. (a/n) ", "a")) {
            String better = runner.assist(p, q, p.answer(q.id()));
            System.out.println("  --- suggestion ---\n" + better + "\n  ---");
            if (yes(in, "  Use the suggestion? (y/n) ", "y")) p.setAnswer(q.id(), better);
        }
    }

    private static String readBlock(Scanner in) {
        StringBuilder sb = new StringBuilder();
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if (line.isBlank()) break;
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    private static boolean yes(Scanner in, String prompt, String key) {
        System.out.print(prompt);
        return in.hasNextLine() && in.nextLine().strip().equalsIgnoreCase(key);
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 100 ? line.substring(0, 100) + "..." : line;
    }
}
