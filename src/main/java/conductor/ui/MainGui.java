package conductor.ui;

import com.formdev.flatlaf.FlatLightLaf;
import conductor.Wiring;
import conductor.config.Config;
import conductor.panel.Panel;
import conductor.panel.Panelist;
import conductor.sdlc.Project;
import conductor.sdlc.ProjectStore;
import conductor.sdlc.Question;
import conductor.sdlc.Questions;
import conductor.sdlc.Stage;
import conductor.sdlc.StageRunner;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The on-rails window: stages on the left, the current stage's form in the
 * centre, that stage's output on the right, cost and progress in the status
 * bar. Agent work runs in SwingWorkers; nothing here throws at the user -
 * failures land in the status bar or a dialog.
 */
public class MainGui extends JFrame {

    private final Wiring wiring;
    private final ProjectStore store = new ProjectStore(Wiring.PROJECTS_DIR);
    private List<Panelist> panelists;
    private StageRunner runner;
    private Project project;
    private Stage shown = Stage.IDEA;
    private boolean navigating;   // true while we set the list selection ourselves, so the listener stays quiet
    private boolean busy;

    private final JLabel nameLabel = new JLabel();
    private final JToolBar toolbar = new JToolBar();
    private final JList<Stage> stageList = new JList<>(Stage.values());
    private final JPanel form = new JPanel();
    private final Map<String, JTextArea> fields = new LinkedHashMap<>();
    private final JTextArea artifactView = textArea("", 0, 0);
    private final JLabel status = new JLabel(" ");
    private final JButton completeButton = new JButton("Complete stage");
    private final JButton handOffButton = new JButton("Hand off to OpenClaw");

    public static void launch() {
        FlatLightLaf.setup();
        Config config;
        try { config = new Config(Config.defaultPath()); }
        catch (IOException e) { fail(e.getMessage()); return; }
        if (!config.hasAllKeys()) { fail("config.properties is missing one or more API keys (anthropic.key, openai.key, gemini.key)."); return; }
        SwingUtilities.invokeLater(() -> new MainGui(new Wiring(config)).setVisible(true));
    }

    private static void fail(String message) {
        JOptionPane.showMessageDialog(null, message, "Conductor cannot start", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    MainGui(Wiring wiring) {
        super("Conductor");
        this.wiring = wiring;
        this.panelists = wiring.loadPanelists();
        this.runner = wiring.runner(panelists, Wiring.loadContext());
        JMenu settings = new JMenu("Settings");
        settings.add(wire(new JMenuItem("Panelists..."), this::editPanelists));
        settings.add(wire(new JMenuItem("Context..."), this::editContext));
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(settings);
        setJMenuBar(menuBar);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildStageList(), BorderLayout.WEST);
        add(buildCentre(), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { saveQuietly(); }
        });
        setSize(1280, 820);
        setLocationRelativeTo(null);
        List<String> slugs = store.listSlugs();
        Project first = slugs.isEmpty() ? null : store.load(slugs.get(0));
        setProject(first != null ? first : new Project("Untitled project"));
    }

    private JToolBar buildToolbar() {
        toolbar.setFloatable(false);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, nameLabel.getFont().getSize() + 3f));
        nameLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 16));
        toolbar.add(nameLabel);
        toolbar.add(wire(new JButton("New project..."), this::newProject));
        toolbar.add(wire(new JButton("Open..."), this::openProject));
        toolbar.add(Box.createHorizontalGlue());
        handOffButton.setToolTipText("Send the build brief to your OpenClaw agent (1 API call)");
        toolbar.add(wire(handOffButton, this::handOff));
        return toolbar;
    }

    private JComponent buildStageList() {
        stageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stageList.setFixedCellHeight(36);
        stageList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        stageList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                Stage s = (Stage) v;
                String mark = project == null ? "" : project.isComplete(s) ? "✓ " : project.current() == s ? "▶ " : "    ";
                return super.getListCellRendererComponent(l, mark + s.title(), i, sel, foc);
            }
        });
        stageList.addListSelectionListener(e -> {
            if (!navigating && !e.getValueIsAdjusting() && stageList.getSelectedValue() != null) showStage(stageList.getSelectedValue());
        });
        JScrollPane scroll = new JScrollPane(stageList);
        scroll.setPreferredSize(new Dimension(210, 0));
        return scroll;
    }

    private JComponent buildCentre() {
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JPanel top = new JPanel(new BorderLayout());   // NORTH keeps the form at its preferred height instead of stretching
        top.add(form, BorderLayout.NORTH);
        JScrollPane formScroll = new JScrollPane(top);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(wire(completeButton, this::completeStage));
        JPanel left = new JPanel(new BorderLayout());
        left.add(formScroll, BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);
        artifactView.setEditable(false);
        artifactView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        artifactView.setMargin(new Insets(8, 8, 8, 8));
        JScrollPane right = new JScrollPane(artifactView);
        right.setBorder(BorderFactory.createTitledBorder("Stage output"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.55);
        return split;
    }

    private void setProject(Project p) {
        saveQuietly();
        fields.clear();   // old fields must not be committed into the new project
        project = p;
        nameLabel.setText(p.name());
        setTitle("Conductor - " + p.name());
        showStage(p.current());
    }

    private void newProject() {
        String name = JOptionPane.showInputDialog(this, "What is the project called?", "New project", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        Project p = new Project(name.strip());
        Project existing = store.exists(p.slug()) ? store.load(p.slug()) : null;
        setProject(existing != null ? existing : p);
        setStatus(existing != null ? "A project with that name already exists - opened it." : "Created project '" + p.name() + "'.");
    }

    private void openProject() {
        List<String> slugs = store.listSlugs();
        if (slugs.isEmpty()) { setStatus("No saved projects yet - use New project."); return; }
        JComboBox<String> box = new JComboBox<>(slugs.toArray(String[]::new));
        if (!confirm(box, "Open project")) return;
        Project p = store.load((String) box.getSelectedItem());
        if (p == null) { setStatus("Could not open '" + box.getSelectedItem() + "' - see the terminal for details."); return; }
        setProject(p);
    }

    private void showStage(Stage s) {
        commitFields();
        shown = s;
        navigating = true;
        stageList.setSelectedValue(s, true);
        navigating = false;
        form.removeAll();
        fields.clear();
        form.add(label("<html><h2>" + s.title() + "</h2>" + s.goal() + "</html>", Font.PLAIN));
        form.add(Box.createVerticalStrut(12));
        for (Question q : Questions.forStage(s)) addQuestion(q);
        form.revalidate();
        form.repaint();
        artifactView.setText(project.artifact(s));
        artifactView.setCaretPosition(0);
        completeButton.setText(project.isComplete(s) ? "Complete stage again (replaces output)" : "Complete stage");
        handOffButton.setVisible(s == Stage.BUILD);
        handOffButton.setEnabled(!busy && wiring.config().openclawEnabled());
        int calls = runner.estimateCalls(project, s);
        setStatus(calls == 0 ? "Completing this stage makes no API calls." : "Completing this stage will make " + calls + " API call" + (calls == 1 ? "." : "s."));
        stageList.repaint();
    }

    private void addQuestion(Question q) {
        JTextArea area = textArea(project.answer(q.id()), 3, 40);
        fields.put(q.id(), area);
        form.add(Box.createVerticalStrut(10));
        form.add(label(q.prompt() + (q.required() ? " *" : ""), Font.BOLD));
        JLabel help = label(q.help(), Font.PLAIN);
        help.setForeground(Color.GRAY);
        help.setFont(help.getFont().deriveFont(help.getFont().getSize() - 1f));
        form.add(help);
        form.add(Box.createVerticalStrut(4));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        form.add(scroll);
        if (q.agentAssist()) {
            JButton assist = wire(new JButton("Ask an agent to sharpen this"), () -> assist(q, area));
            assist.setToolTipText("1 API call to " + wiring.lead().modelName());
            assist.setEnabled(!busy);
            assist.setAlignmentX(LEFT_ALIGNMENT);
            form.add(Box.createVerticalStrut(4));
            form.add(assist);
        }
    }

    private void completeStage() {
        commitFields();
        Stage s = shown;
        List<String> missing = Questions.forStage(s).stream()
                .filter(q -> q.required() && project.answer(q.id()).isBlank()).map(Question::prompt).toList();
        if (!missing.isEmpty()) { setStatus("Please answer first: " + String.join(" | ", missing)); return; }
        int calls = runner.estimateCalls(project, s);
        if (calls > 0 && !confirm("Completing " + s.title() + " will make " + calls + " API call(s). Continue?", "Conductor")) return;
        artifactView.setText("");   // the debate transcript streams in here while the panel works
        runInBackground("Completing " + s.title() + "...", () -> runner.complete(project, s, listener()), artifact -> {
            project.setArtifact(s, artifact);
            if (s.ordinal() >= project.current().ordinal()) project.setCurrent(s.next());
            saveQuietly();
            showStage(s);
            setStatus(s.isLast() ? "All stages complete." : s.title() + " complete - next up: " + s.next().title() + ".");
        });
    }

    private void assist(Question q, JTextArea area) {
        commitFields();
        String draft = area.getText();
        runInBackground("Asking " + wiring.lead().modelName() + " to sharpen your answer (1 API call)...",
                () -> runner.assist(project, q, draft),
                better -> { area.setText(better); setStatus("Suggestion inserted - edit it freely, it is still your answer."); });
    }

    private void handOff() {
        commitFields();
        if (!project.isComplete(Stage.BUILD)) { setStatus("Complete the Build stage first - there is no brief to hand off."); return; }
        if (!confirm("Send the build brief to OpenClaw? This will make 1 API call.", "Conductor")) return;
        runInBackground("Handing off to OpenClaw...", () -> runner.handOffToOpenClaw(project), reply -> {
            artifactView.setText(project.artifact(Stage.BUILD) + "\n\n---\n## OpenClaw reply\n\n" + reply);
            artifactView.setCaretPosition(artifactView.getDocument().getLength());
            setStatus("OpenClaw replied - see the end of the stage output.");
        });
    }

    private Panel.Listener listener() {
        return (kind, who, text) -> SwingUtilities.invokeLater(() -> {
            if (kind.equals("status")) setStatus(text);
            else artifactView.append("### " + who + " (" + kind + ")\n\n" + text.strip() + "\n\n");
        });
    }

    private <T> void runInBackground(String message, Supplier<T> work, Consumer<T> onDone) {
        setBusy(true);
        setStatus(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return work.get(); }
            @Override protected void done() {
                setBusy(false);
                try { onDone.accept(get()); }
                catch (Exception e) { setStatus("Something went wrong: " + (e.getCause() != null ? e.getCause() : e)); }
            }
        }.execute();
    }

    private void editPanelists() {
        Box box = Box.createVerticalBox();
        List<JTextComponent[]> rows = new ArrayList<>();
        for (Panelist p : panelists) {
            JTextComponent[] row = { new JTextField(p.name()), new JTextField(p.perspective()), textArea(p.lens(), 3, 50) };
            JPanel head = new JPanel(new GridLayout(2, 2, 8, 2));
            for (Component c : List.of(new JLabel("Name"), row[0], new JLabel("Perspective"), row[1])) head.add(c);
            JPanel one = new JPanel(new BorderLayout(0, 4));
            one.setBorder(BorderFactory.createTitledBorder(p.name()));
            one.add(head, BorderLayout.NORTH);
            one.add(new JScrollPane(row[2]), BorderLayout.CENTER);
            box.add(one);
            rows.add(row);
        }
        if (!confirm(box, "Panelists")) return;
        List<Panelist> edited = rows.stream()
                .map(r -> new Panelist(r[0].getText().strip(), r[1].getText().strip(), r[2].getText().strip())).toList();
        try {
            Panelist.saveAll(Wiring.PANELISTS_FILE, edited);
            panelists = edited;
            runner = wiring.runner(panelists, Wiring.loadContext());
            setStatus("Panel saved to " + Wiring.PANELISTS_FILE + ".");
        } catch (RuntimeException e) { setStatus("Could not save panelists: " + e.getMessage()); }
    }

    private void editContext() {
        JTextArea area = textArea(Wiring.loadContext(), 15, 70);
        if (!confirm(new JScrollPane(area), "Context sent with every agent call")) return;
        try {
            Files.writeString(Wiring.CONTEXT_FILE, area.getText());
            runner = wiring.runner(panelists, area.getText());
            setStatus("Context saved to " + Wiring.CONTEXT_FILE + ".");
        } catch (IOException e) { setStatus("Could not save context: " + e.getMessage()); }
    }

    private void commitFields() {
        if (project == null) return;
        fields.forEach((id, area) -> { if (!area.getText().equals(project.answer(id))) project.setAnswer(id, area.getText()); });
    }

    private void saveQuietly() {
        if (project == null) return;
        commitFields();
        try { store.save(project); }
        catch (RuntimeException e) { setStatus("Could not save: " + e.getMessage()); }
    }

    private void setBusy(boolean b) {
        busy = b;
        setCursor(b ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        completeButton.setEnabled(!b);
        for (Component c : toolbar.getComponents()) if (c instanceof AbstractButton button) button.setEnabled(!b);
        for (Component c : form.getComponents()) if (c instanceof AbstractButton button) button.setEnabled(!b);
        handOffButton.setEnabled(!b && wiring.config().openclawEnabled());
    }

    private void setStatus(String text) { status.setText(text); }

    /** OK/Cancel dialog around any component (or a plain question). True when the user said OK. */
    private boolean confirm(Object content, String title) {
        return JOptionPane.showConfirmDialog(this, content, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static <B extends AbstractButton> B wire(B button, Runnable action) {
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JTextArea textArea(String text, int rows, int cols) {
        JTextArea area = new JTextArea(text, rows, cols);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JLabel label(String text, int style) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(style));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }
}
