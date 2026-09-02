package conductor.panel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelistTest {

    @Test
    void briefingMatchesLegacyFormat() {
        var p = new Panelist("Claude", "Architecture & Quality", "Priorities: x.");
        assertEquals("=== YOUR AGENT IDENTITY ===\nAgent name: Claude\nPerspective: Architecture & Quality\nPriorities: x.\n\n",
                p.briefing());
    }

    @Test
    void roundTripsThroughJsonAndFallsBackToDefaults(@TempDir Path dir) throws Exception {
        var file = dir.resolve("agents.json");
        assertEquals(Panelist.defaults(), Panelist.loadAll(file), "missing file -> defaults");

        var custom = List.of(new Panelist("X", "p", "l"), new Panelist("Y", "q", "m"));
        Panelist.saveAll(file, custom);
        assertEquals(custom, Panelist.loadAll(file));

        Files.writeString(file, "[]");
        assertEquals(Panelist.defaults(), Panelist.loadAll(file), "empty array -> defaults");
        Files.writeString(file, "not json");
        assertEquals(Panelist.defaults(), Panelist.loadAll(file), "garbage -> defaults");
        assertEquals(3, Panelist.defaults().size());
    }

    @Test
    void missingJsonFieldsNeverBecomeNull(@TempDir Path dir) throws Exception {
        var file = dir.resolve("agents.json");
        Files.writeString(file, "[{\"name\":\"A\"},{\"name\":\"B\",\"perspective\":\"p\"}]");
        var loaded = Panelist.loadAll(file);
        assertEquals(2, loaded.size());
        assertEquals("", loaded.get(0).perspective());
        assertEquals("", loaded.get(0).lens());
        assertFalse(loaded.get(0).briefing().contains("null"));

        Files.writeString(file, "[{\"perspective\":\"no name\"},{\"name\":\"B\"}]");
        assertEquals(Panelist.defaults(), Panelist.loadAll(file), "a seat without a name -> defaults");
        Files.writeString(file, "[null,{\"name\":\"B\"}]");
        assertEquals(Panelist.defaults(), Panelist.loadAll(file), "a null seat -> defaults");
        assertEquals("", new Panelist(null, null, null).name());
    }
}
