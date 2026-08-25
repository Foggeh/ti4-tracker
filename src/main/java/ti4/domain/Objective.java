package ti4.domain;

/** A public objective card in the catalogue. */
public record Objective(
        long id,
        String name,
        String stage,
        int points,
        String requirement,
        String expansion,
        String image) {
}
