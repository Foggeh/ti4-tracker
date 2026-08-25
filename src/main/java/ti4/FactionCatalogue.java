package ti4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ti4.domain.Faction;

/**
 * The faction list for the add-player dropdown, read from
 * {@code data/factions.csv} at startup.
 *
 * <p>Not a database table on purpose: it is reference data that games never
 * mutate, so a file read into memory is the whole requirement. Editing the CSV
 * and restarting is the update path.
 */
@Component
public class FactionCatalogue {

    private static final Logger log = LoggerFactory.getLogger(FactionCatalogue.class);

    private final List<Faction> factions;

    public FactionCatalogue(Ti4Properties properties) {
        this.factions = load(Path.of(properties.factionsFile()));
        log.info("Loaded {} factions.", factions.size());
    }

    public List<Faction> all() {
        return factions;
    }

    private static List<Faction> load(Path file) {
        if (!Files.exists(file)) {
            // Degraded rather than fatal: an empty dropdown still lets the rest
            // of the app run, and the log says why.
            log.warn("No faction file at {} -- the faction dropdown will be empty.",
                    file.toAbsolutePath());
            return List.of();
        }

        List<Faction> loaded = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
                String line = lines.get(lineNo - 1);
                if (Csv.isSkippable(line)) {
                    continue;
                }
                List<String> fields = Csv.parseLine(line.strip());
                if (fields.size() < 2) {
                    log.warn("Faction line {} has {} fields, expected 2; skipped.",
                            lineNo, fields.size());
                    continue;
                }
                if (fields.get(0).equalsIgnoreCase("expansion")) {
                    continue; // header
                }
                loaded.add(new Faction(fields.get(0), fields.get(1)));
            }
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file.toAbsolutePath(), e.getMessage());
            return List.of();
        }
        return List.copyOf(loaded);
    }
}
