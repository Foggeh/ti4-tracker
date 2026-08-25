package ti4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds a card scan for an objective by name, so images need no data entry:
 * name the file after the card and it is picked up.
 *
 * <p>Matching is deliberately forgiving. "Erect a Monument" is found by
 * {@code erect-a-monument.jpg}, {@code Erect A Monument.PNG},
 * {@code erect_a_monument.jpeg} or {@code erectamonument.webp}. An explicit
 * {@code image} value on the objective always wins over this.
 */
@Component
public class ImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageResolver.class);

    private static final List<String> EXTENSIONS =
            List.of("jpg", "jpeg", "png", "webp", "gif", "avif");

    /**
     * Directory listings are cached briefly: long enough that one request does
     * not stat the disk once per card, short enough that dropping a file in
     * shows up on the next refresh without a restart.
     */
    private static final long CACHE_MS = 2_000;

    private final Path dir;

    private volatile Map<String, String> index = Map.of();
    private volatile long indexedAt = 0;

    public ImageResolver(Ti4Properties properties) {
        this.dir = Path.of(properties.imagesDir());
    }

    /** The filename to serve for this card, or null if there is no match. */
    public String resolve(String objectiveName) {
        if (objectiveName == null) {
            return null;
        }
        Map<String, String> current = index();
        String hit = current.get(slug(objectiveName));
        return hit != null ? hit : current.get(compact(objectiveName));
    }

    private Map<String, String> index() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = index;
        if (now - indexedAt < CACHE_MS) {
            return cached;
        }

        Map<String, String> fresh = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String fileName = path.getFileName().toString();
                if (!isImage(fileName)) {
                    return;
                }
                String base = stripExtension(fileName);
                // putIfAbsent: if two files collide on a slug, first wins
                // rather than flapping between them run to run.
                fresh.putIfAbsent(slug(base), fileName);
                fresh.putIfAbsent(compact(base), fileName);
            });
        } catch (IOException e) {
            // A missing images dir is normal before any scans are added.
            log.debug("Could not list {}: {}", dir.toAbsolutePath(), e.getMessage());
        }

        index = fresh;
        indexedAt = now;
        return fresh;
    }

    private static boolean isImage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** "Erect a Monument" -> "erect-a-monument" */
    static String slug(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** "Erect a Monument" -> "erectamonument", for filenames with no separators. */
    static String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
