package ti4;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ti4.domain.Game;
import ti4.domain.LedgerRow;
import ti4.domain.Player;
import ti4.domain.Standings;
import ti4.domain.StandingsRow;

/**
 * Turns a game's ledger into the end-of-game standings.
 *
 * <p>Deliberately a plain class with no Spring and no database: everything it
 * needs is passed in. That keeps the arithmetic — the breakdown, the column
 * selection, the ranking — testable directly, rather than only through HTTP.
 *
 * <p>It derives from the same ledger rows the screen renders, so the report and
 * the running totals cannot drift apart.
 */
public final class StandingsCalculator {

    private StandingsCalculator() {
    }

    /**
     * @param ledger rows in id order, which is chronological; that order decides
     *               the sequence of the other-source columns
     * @param now    passed in rather than read from the clock, so a test can
     *               assert on the value
     */
    public static Standings calculate(
            Game game, List<Player> players, List<LedgerRow> ledger, Instant now) {

        List<String> otherColumns = new ArrayList<>();
        Map<Long, Tally> tallies = new LinkedHashMap<>();
        for (Player p : players) {
            tallies.put(p.id(), new Tally());
        }

        for (LedgerRow row : ledger) {
            Tally tally = tallies.get(row.playerId());
            if (tally == null) {
                continue; // scores cascade with their player, so this should not happen
            }
            switch (row.kind()) {
                case "public" -> {
                    tally.publicPoints += row.points();
                    tally.publicCount++;
                }
                case "secret" -> {
                    tally.secretPoints += row.points();
                    tally.secretCount++;
                }
                default -> {
                    String label = otherLabel(row);
                    tally.otherPoints += row.points();
                    tally.bySource.merge(label, row.points(), Integer::sum);
                    if (!otherColumns.contains(label)) {
                        otherColumns.add(label);
                    }
                }
            }
        }

        List<StandingsRow> sorted = players.stream()
                .map(p -> tallies.get(p.id()).toRow(p))
                .sorted(Comparator.comparingInt(StandingsRow::total).reversed())
                .toList();

        return new Standings(
                game,
                now.toString(),
                List.copyOf(otherColumns),
                rank(sorted),
                winners(sorted),
                !sorted.isEmpty() && sorted.get(0).total() >= game.vpTarget(),
                ledger.size());
    }

    /** An other-kind row is always manual, so its label is all there is. */
    private static String otherLabel(LedgerRow row) {
        if (row.objectiveName() != null) {
            return row.objectiveName();
        }
        return row.label() == null || row.label().isBlank() ? "Unlabelled" : row.label();
    }

    /**
     * Competition ranking over an already-sorted list: 1, 2, 2, 4.
     *
     * <p>The rules break a tie by initiative order, which this app does not
     * track, so a tie is reported as a tie and the table settles it.
     */
    private static List<StandingsRow> rank(List<StandingsRow> sorted) {
        List<StandingsRow> ranked = new ArrayList<>(sorted.size());
        int rank = 0;
        Integer previousTotal = null;
        for (StandingsRow r : sorted) {
            if (previousTotal == null || r.total() != previousTotal) {
                rank = ranked.size() + 1;
                previousTotal = r.total();
            }
            ranked.add(new StandingsRow(r.playerId(), r.name(), r.faction(), r.color(),
                    rank, r.total(), r.publicPoints(), r.publicCount(),
                    r.secretPoints(), r.secretCount(), r.otherPoints(), r.bySource()));
        }
        return List.copyOf(ranked);
    }

    /** Everyone on the top score, or nobody at all while the board is empty. */
    private static List<String> winners(List<StandingsRow> sorted) {
        if (sorted.isEmpty() || sorted.get(0).total() <= 0) {
            return List.of();
        }
        int best = sorted.get(0).total();
        return sorted.stream().filter(r -> r.total() == best).map(StandingsRow::name).toList();
    }

    /** Mutable accumulator; only {@link #toRow} escapes. */
    private static final class Tally {
        private int publicPoints;
        private int publicCount;
        private int secretPoints;
        private int secretCount;
        private int otherPoints;
        private final Map<String, Integer> bySource = new LinkedHashMap<>();

        private StandingsRow toRow(Player p) {
            return new StandingsRow(
                    p.id(), p.name(), p.faction(), p.color(), 0,
                    publicPoints + secretPoints + otherPoints,
                    publicPoints, publicCount, secretPoints, secretCount, otherPoints,
                    Map.copyOf(bySource));
        }
    }
}
