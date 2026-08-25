package ti4.domain;

import java.util.List;

/** Everything the browser needs for one render. */
public record GameState(
        Game game,
        List<PlayerState> players,
        List<RevealedObjective> revealed,
        List<LedgerRow> ledger) {
}
