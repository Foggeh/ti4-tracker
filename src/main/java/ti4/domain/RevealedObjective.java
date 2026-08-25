package ti4.domain;

import java.util.List;

/** A public objective that is face-up in a particular game. */
public record RevealedObjective(
        long id,
        String name,
        String stage,
        int points,
        String requirement,
        String image,
        Integer round,
        List<Long> scoredBy) {
}
