package ti4.domain;

import java.util.List;

/**
 * The end-of-game report for one game: every player's total, split into public
 * objectives, secret objectives, and one column per other source.
 *
 * <p>Derived from the same ledger the screen renders, so the report and the
 * running totals cannot drift apart.
 *
 * @param otherColumns labels of the other-kind ledger rows, in the order they
 *                     were first scored. A source nobody scored is absent
 *                     entirely, which is what keeps an empty Shard of the Throne
 *                     column off the printed page.
 * @param winners      everyone on the top score. More than one name is a tie;
 *                     empty means nothing has been scored yet.
 * @param reachedTarget whether the top score actually met the game's VP target,
 *                     which is what separates a finished game from a snapshot
 * @param entryCount   ledger rows behind the report, printed as a reconciliation
 *                     aid when a total is disputed afterwards
 */
public record Standings(
        Game game,
        String generatedAt,
        List<String> otherColumns,
        List<StandingsRow> rows,
        List<String> winners,
        boolean reachedTarget,
        int entryCount) {
}
