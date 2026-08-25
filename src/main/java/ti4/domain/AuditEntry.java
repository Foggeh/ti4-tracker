package ti4.domain;

/**
 * One recorded action.
 *
 * @param actor best available identity: there is no login, so this is the device
 *              address the request came from, which at least separates the
 *              laptop from each phone at the table
 */
public record AuditEntry(
        long id,
        Long gameId,
        String action,
        String detail,
        String actor,
        String createdAt) {
}
