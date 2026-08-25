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

import ti4.domain.PointSource;

/**
 * Seed options for the Add-points Label dropdown, from
 * {@code data/point-sources.csv}.
 *
 * <p>Only the seeded half. Labels already used in a game are merged in by
 * {@code ApiController}, so the list grows as it is used and the file never has
 * to be complete.
 */
@Component
public class PointSourceCatalogue {

    private static final Logger log = LoggerFactory.getLogger(PointSourceCatalogue.class);

    private final List<PointSource> sources;

    public PointSourceCatalogue(Ti4Properties properties) {
        this.sources = load(Path.of(properties.pointSourcesFile()));
        log.info("Loaded {} seeded point sources.", sources.size());
    }

    public List<PointSource> all() {
        return sources;
    }

    private static List<PointSource> load(Path file) {
        if (!Files.exists(file)) {
            log.warn("No point-source file at {} -- the label dropdown starts from "
                    + "whatever has been used in games.", file.toAbsolutePath());
            return List.of();
        }

        List<PointSource> loaded = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
                String line = lines.get(lineNo - 1);
                if (Csv.isSkippable(line)) {
                    continue;
                }
                List<String> fields = Csv.parseLine(line.strip());
                if (fields.size() < 3) {
                    log.warn("Point-source line {} has {} fields, expected 3; skipped.",
                            lineNo, fields.size());
                    continue;
                }
                if (fields.get(0).equalsIgnoreCase("kind")) {
                    continue; // header
                }

                String kind = fields.get(0);
                if (!kind.equals("secret") && !kind.equals("other")) {
                    log.warn("Point-source line {} has kind '{}', expected secret or other; "
                            + "skipped.", lineNo, kind);
                    continue;
                }
                try {
                    loaded.add(new PointSource(kind, Integer.parseInt(fields.get(1)),
                            fields.get(2), false));
                } catch (NumberFormatException e) {
                    log.warn("Point-source line {} has non-numeric points '{}'; skipped.",
                            lineNo, fields.get(1));
                }
            }
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file.toAbsolutePath(), e.getMessage());
            return List.of();
        }
        return List.copyOf(loaded);
    }
}
