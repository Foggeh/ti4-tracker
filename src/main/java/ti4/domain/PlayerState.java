package ti4.domain;

/**
 * A player plus their derived numbers.
 *
 * @param secretCount how many secret-objective entries they hold, used to warn
 *                    when the 3 (or 4, with The Obsidian) cap is exceeded
 */
public record PlayerState(
        long id,
        String name,
        String faction,
        String color,
        int total,
        int secretCount) {
}
