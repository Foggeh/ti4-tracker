package ti4.domain;

/**
 * An option for the Add-points Label dropdown.
 *
 * @param learned true when this came from a label already used in a game rather
 *                than from the seed file, so the UI can mark it
 */
public record PointSource(String kind, int points, String name, boolean learned) {
}
