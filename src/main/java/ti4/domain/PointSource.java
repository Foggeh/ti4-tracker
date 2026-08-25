package ti4.domain;

/**
 * An option for the Add-points Label dropdown.
 *
 * @param group   heading it sits under in the dropdown, e.g. the phase a secret
 *                objective is scored in. May be null.
 * @param learned true when this came from a label already used in a game rather
 *                than from the seed file, so the UI can group it separately
 */
public record PointSource(String kind, int points, String name, String group, boolean learned) {
}
