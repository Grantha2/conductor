package conductor;

import conductor.agents.AgentClient;
import conductor.config.Clients;
import conductor.config.Config;
import conductor.panel.Panel;
import conductor.panel.Panelist;
import conductor.sdlc.StageRunner;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns config, clients, panelists and org context into one StageRunner.
 * Shared by the GUI and the CLI so both make the same wiring decisions: the
 * lead is client 0, OpenClaw is a hand-off target and never a debate seat,
 * and the panel gets exactly one client per panelist (Panel insists on it).
 */
public final class Wiring {

    public static final Path PANELISTS_FILE = Path.of("panelists.json");
    public static final Path CONTEXT_FILE = Path.of("context.txt");
    public static final Path PROJECTS_DIR = Path.of("projects");

    private final Config config;
    private final List<AgentClient> clients;

    public Wiring(Config config) {
        this.config = config;
        this.clients = Clients.build(config, HttpClient.newHttpClient());
    }

    public Config config() { return config; }

    public AgentClient lead() { return clients.get(0); }

    public AgentClient openclawOrNull() {
        return config.openclawEnabled() ? clients.get(clients.size() - 1) : null;
    }

    /** The debate seats: every client except the OpenClaw gateway. */
    public List<AgentClient> panelClients() {
        return openclawOrNull() == null ? clients : clients.subList(0, clients.size() - 1);
    }

    /** Panelists from disk; defaults when the file's seat count does not match the clients we have. */
    public List<Panelist> loadPanelists() {
        List<Panelist> loaded = Panelist.loadAll(PANELISTS_FILE);
        if (loaded.size() == panelClients().size()) return loaded;
        System.err.println("Wiring: " + PANELISTS_FILE + " has " + loaded.size() + " panelists for "
                + panelClients().size() + " seats; using the defaults.");
        return Panelist.defaults();
    }

    public static String loadContext() {
        try {
            return Files.isRegularFile(CONTEXT_FILE) ? Files.readString(CONTEXT_FILE) : "";
        } catch (IOException e) {
            System.err.println("Wiring: cannot read " + CONTEXT_FILE + ": " + e.getMessage());
            return "";
        }
    }

    public StageRunner runner(List<Panelist> panelists, String orgContext) {
        Panel panel = new Panel(panelClients(), panelists, config.debateRounds(), config.maxTokens());
        return new StageRunner(panel, lead(), openclawOrNull(), config.maxTokens(), orgContext);
    }
}
