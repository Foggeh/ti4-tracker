package ti4;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ti4.domain.Game;
import ti4.domain.LedgerRow;
import ti4.domain.Player;
import ti4.domain.Standings;
import ti4.domain.StandingsRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-of-game arithmetic, with no database and no server.
 *
 * <p>These are the rules that are easy to get wrong and impossible to notice
 * going wrong at the table: a column for a source nobody scored, a tie silently
 * resolved in favour of whoever was added first, a total that stops matching
 * the parts it is made of.
 */
class StandingsCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-15T20:00:00Z");
    private static final Game GAME = new Game(1, "Test", NOW.toString(), 10, 3, false);

    private static Player player(long id, String name) {
        return new Player(id, 1, name, "Some Faction", null, (int) id);
    }

    /** A scored public objective: it carries a card, so it has an objective name. */
    private static LedgerRow publicRow(long id, long playerId, String card, int points) {
        return new LedgerRow(id, playerId, 99L, card, points, null, "public");
    }

    /** A manual entry: no card behind it, so the label is the only description. */
    private static LedgerRow manualRow(long id, long playerId, String label, int points, String kind) {
        return new LedgerRow(id, playerId, null, null, points, label, kind);
    }

    @Test
    @DisplayName("splits a total into public, secret and other")
    void splitsATotal() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), List.of(
                publicRow(1, 1, "Amass Wealth", 1),
                publicRow(2, 1, "Achieve Supremacy", 2),
                manualRow(3, 1, "Become a Martyr", 1, "secret"),
                manualRow(4, 1, "Custodians token", 1, "other")), NOW);

        StandingsRow row = s.rows().get(0);
        assertThat(row.total()).isEqualTo(5);
        assertThat(row.publicPoints()).isEqualTo(3);
        assertThat(row.publicCount()).isEqualTo(2);
        assertThat(row.secretPoints()).isEqualTo(1);
        assertThat(row.secretCount()).isEqualTo(1);
        assertThat(row.otherPoints()).isEqualTo(1);
    }

    @Test
    @DisplayName("gives a column only to sources somebody actually scored")
    void omitsUnscoredSources() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), List.of(
                manualRow(1, 1, "Custodians token", 1, "other")), NOW);

        assertThat(s.otherColumns()).containsExactly("Custodians token");
        assertThat(s.otherColumns()).doesNotContain("Shard of the Throne");
    }

    @Test
    @DisplayName("orders columns by when each source was first scored")
    void ordersColumnsByFirstUse() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana"), player(2, "Bo")),
                List.of(
                        manualRow(1, 1, "Imperial Rider", 1, "other"),
                        manualRow(2, 2, "Custodians token", 1, "other"),
                        manualRow(3, 1, "Shard of the Throne", 1, "other")), NOW);

        assertThat(s.otherColumns())
                .containsExactly("Imperial Rider", "Custodians token", "Shard of the Throne");
    }

    @Test
    @DisplayName("sums a source scored more than once by the same player")
    void sumsRepeatsOfOneSource() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), List.of(
                manualRow(1, 1, "Support for the Throne", 1, "other"),
                manualRow(2, 1, "Support for the Throne", 1, "other")), NOW);

        assertThat(s.otherColumns()).containsExactly("Support for the Throne");
        assertThat(s.rows().get(0).bySource()).containsEntry("Support for the Throne", 2);
    }

    @Test
    @DisplayName("ranks tied players equally and skips the rank they share")
    void tiesShareARank() {
        Standings s = StandingsCalculator.calculate(GAME,
                List.of(player(1, "Ana"), player(2, "Bo"), player(3, "Cid")),
                List.of(
                        publicRow(1, 1, "Achieve Supremacy", 2),
                        publicRow(2, 2, "Become a Legend", 2),
                        publicRow(3, 3, "Amass Wealth", 1)), NOW);

        assertThat(s.rows()).extracting(StandingsRow::rank).containsExactly(1, 1, 3);
        assertThat(s.winners()).containsExactlyInAnyOrder("Ana", "Bo");
    }

    @Test
    @DisplayName("names nobody a winner while the board is empty")
    void noWinnerBeforeAnyonePoints() {
        Standings s = StandingsCalculator.calculate(GAME,
                List.of(player(1, "Ana"), player(2, "Bo")), List.of(), NOW);

        assertThat(s.winners()).isEmpty();
        assertThat(s.reachedTarget()).isFalse();
        assertThat(s.rows()).hasSize(2);
        assertThat(s.rows()).allMatch(r -> r.total() == 0);
    }

    @Test
    @DisplayName("reports the target as reached only once someone is actually on it")
    void reachedTargetTracksTheVpTarget() {
        List<LedgerRow> nine = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            nine.add(publicRow(i, 1, "Card " + i, 1));
        }
        assertThat(StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), nine, NOW)
                .reachedTarget()).isFalse();

        nine.add(publicRow(10, 1, "Card 10", 1));
        assertThat(StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), nine, NOW)
                .reachedTarget()).isTrue();
    }

    @Test
    @DisplayName("still lists a player who scored nothing")
    void listsPlayersWithNoPoints() {
        Standings s = StandingsCalculator.calculate(GAME,
                List.of(player(1, "Ana"), player(2, "Bo")),
                List.of(publicRow(1, 1, "Amass Wealth", 1)), NOW);

        assertThat(s.rows()).extracting(StandingsRow::name).containsExactly("Ana", "Bo");
        assertThat(s.rows().get(1).total()).isZero();
        assertThat(s.rows().get(1).bySource()).isEmpty();
    }

    @Test
    @DisplayName("keeps every total equal to the parts it is made of")
    void totalsReconcileWithTheirParts() {
        Standings s = StandingsCalculator.calculate(GAME,
                List.of(player(1, "Ana"), player(2, "Bo")),
                List.of(
                        publicRow(1, 1, "Amass Wealth", 1),
                        manualRow(2, 1, "Become a Martyr", 1, "secret"),
                        manualRow(3, 1, "Custodians token", 1, "other"),
                        manualRow(4, 2, "Imperial Rider", 1, "other")), NOW);

        assertThat(s.rows()).allSatisfy(r -> {
            assertThat(r.total())
                    .isEqualTo(r.publicPoints() + r.secretPoints() + r.otherPoints());
            assertThat(r.otherPoints())
                    .isEqualTo(r.bySource().values().stream().mapToInt(Integer::intValue).sum());
        });
    }

    @Test
    @DisplayName("labels an unlabelled manual entry rather than dropping it")
    void unlabelledRowsStillGetAColumn() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), List.of(
                new LedgerRow(1, 1, null, null, 1, "   ", "other")), NOW);

        assertThat(s.otherColumns()).containsExactly("Unlabelled");
        assertThat(s.rows().get(0).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports how many ledger rows it was built from")
    void reportsTheEntryCount() {
        Standings s = StandingsCalculator.calculate(GAME, List.of(player(1, "Ana")), List.of(
                publicRow(1, 1, "Amass Wealth", 1),
                manualRow(2, 1, "Custodians token", 1, "other")), NOW);

        assertThat(s.entryCount()).isEqualTo(2);
        assertThat(s.generatedAt()).isEqualTo(NOW.toString());
    }
}
