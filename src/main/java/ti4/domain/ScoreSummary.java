package ti4.domain;

/** Enough about a score row to describe it in the audit log before deleting it. */
public record ScoreSummary(
        long gameId,
        String playerName,
        int points,
        String what,
        String kind) {
}
