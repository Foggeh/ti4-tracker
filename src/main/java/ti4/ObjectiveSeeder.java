package ti4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import ti4.repo.CatalogueRepository;

/**
 * Loads the objective catalogue from CSV on first boot.
 *
 * <p>Runs only when the table is empty, so hand-edits made through the app are
 * never clobbered by the file. To re-import after editing the CSV, delete
 * {@code data/ti4.db} (losing game history) or add the rows in the app.
 */
@Component
public class ObjectiveSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObjectiveSeeder.class);

    private final CatalogueRepository catalogue;
    private final Ti4Properties properties;

    public ObjectiveSeeder(CatalogueRepository catalogue, Ti4Properties properties) {
        this.catalogue = catalogue;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int existing = catalogue.count();
        if (existing > 0) {
            log.info("Objective catalogue already holds {} cards; skipping seed.", existing);
            return;
        }

        Path seed = Path.of(properties.seedFile());
        if (!Files.exists(seed)) {
            log.warn("No seed file at {} -- catalogue starts empty. Add cards in the app.",
                    seed.toAbsolutePath());
            return;
        }

        int inserted = 0;
        int skipped = 0;
        List<String> lines = Files.readAllLines(seed, StandardCharsets.UTF_8);

        for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
            String raw = lines.get(lineNo - 1);
            if (Csv.isSkippable(raw)) {
                continue;
            }
            String line = raw.strip();

            List<String> fields = Csv.parseLine(line);
            if (fields.size() < 4) {
                log.warn("Seed line {} has {} fields, expected at least 4; skipped.",
                        lineNo, fields.size());
                skipped++;
                continue;
            }
            if (fields.get(0).equalsIgnoreCase("expansion")) {
                continue; // header row
            }

            String expansion = fields.get(0);
            String stage = fields.get(1);
            String pointsText = fields.get(2);
            String name = fields.get(3);
            String requirement = fields.size() > 4 ? Csv.emptyToNull(fields.get(4)) : null;
            String image = fields.size() > 5 ? Csv.emptyToNull(fields.get(5)) : null;

            int points;
            try {
                points = Integer.parseInt(pointsText);
            } catch (NumberFormatException e) {
                log.warn("Seed line {} has non-numeric points '{}'; skipped.", lineNo, pointsText);
                skipped++;
                continue;
            }

            if (!stage.equals("I") && !stage.equals("II")) {
                log.warn("Seed line {} has stage '{}', expected I or II; skipped.", lineNo, stage);
                skipped++;
                continue;
            }

            try {
                catalogue.insert(name, stage, points, requirement, expansion, image);
                inserted++;
            } catch (RuntimeException e) {
                // Almost always a duplicate name, which is a data problem worth
                // naming rather than a reason to abort the whole import.
                log.warn("Seed line {} ('{}') rejected: {}", lineNo, name, e.getMessage());
                skipped++;
            }
        }

        log.info("Seeded {} objectives from {} ({} skipped).", inserted, seed, skipped);
        if (skipped > 0) {
            log.warn("{} seed rows were skipped -- see the warnings above for line numbers.",
                    skipped);
        }
        logBreakdown();
    }

    /**
     * Logs what actually landed, grouped by expansion and stage. Cheap way to
     * spot a bad import: the expected shape is 10 of each stage for base and
     * 10 of each for pok. Thunder's Edge adds no public objectives, so a
     * thunders-edge row here means a homebrew or mis-tagged card.
     */
    private void logBreakdown() {
        Map<String, Long> byGroup = catalogue.all().stream()
                .collect(Collectors.groupingBy(
                        o -> o.expansion() + " stage " + o.stage(),
                        TreeMap::new,
                        Collectors.counting()));
        byGroup.forEach((group, count) -> log.info("  {}: {}", group, count));
    }

}
