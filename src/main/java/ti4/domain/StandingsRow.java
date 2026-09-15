package ti4.domain;

import java.util.Map;

/**
 * One player's line in the end-of-game standings.
 *
 * @param rank         competition ranking, so two players tied on 9 VP are both
 *                     2nd and the next one down is 4th
 * @param publicCount  how many public objective cards, as opposed to points
 * @param bySource     points from each other source, keyed by the ledger label.
 *                     Iterate {@link Standings#otherColumns()} rather than this
 *                     map's own order when laying out a table; a player who
 *                     scored nothing from a source is simply absent here.
 */
public record StandingsRow(
        long playerId,
        String name,
        String faction,
        String color,
        int rank,
        int total,
        int publicPoints,
        int publicCount,
        int secretPoints,
        int secretCount,
        int otherPoints,
        Map<String, Integer> bySource) {
}
