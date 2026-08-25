package ti4.domain;

/** One scoring event. {@code objectiveName} is null for manual entries. */
public record LedgerRow(
        long id,
        long playerId,
        Long objectiveId,
        String objectiveName,
        int points,
        String label,
        String kind) {
}
