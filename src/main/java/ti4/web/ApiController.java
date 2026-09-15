package ti4.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ti4.domain.Game;
import ti4.domain.GameState;
import ti4.domain.LedgerRow;
import ti4.domain.Objective;
import ti4.domain.Player;
import ti4.domain.PlayerState;
import ti4.domain.RevealedObjective;
import ti4.FactionCatalogue;
import ti4.PointSourceCatalogue;
import ti4.ImageResolver;
import ti4.domain.Faction;
import ti4.domain.AuditEntry;
import ti4.domain.PointSource;
import ti4.domain.ScoreSummary;
import ti4.domain.Standings;
import ti4.domain.StandingsRow;
import ti4.repo.AuditRepository;
import ti4.repo.CatalogueRepository;
import ti4.repo.GameRepository;

/**
 * The whole API. Request bodies are form-encoded, responses are JSON.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final GameRepository games;
    private final CatalogueRepository catalogue;
    private final ImageResolver images;
    private final FactionCatalogue factions;
    private final PointSourceCatalogue pointSources;
    private final AuditRepository audit;

    /**
     * A Spring-provided request-scoped proxy, so the audit helper can name the
     * calling device without threading it through nine method signatures.
     */
    private final HttpServletRequest request;

    public ApiController(GameRepository games, CatalogueRepository catalogue,
                         ImageResolver images, FactionCatalogue factions,
                         PointSourceCatalogue pointSources, AuditRepository audit,
                         HttpServletRequest request) {
        this.games = games;
        this.catalogue = catalogue;
        this.images = images;
        this.factions = factions;
        this.pointSources = pointSources;
        this.audit = audit;
        this.request = request;
    }

    /** There is no login, so the device address is the only identity available. */
    private void log(Long gameId, String action, String detail) {
        audit.record(gameId, action, detail, actor());
    }

    /**
     * Loopback comes back as "0:0:0:0:0:0:0:1" or "127.0.0.1", which tells a
     * reader nothing. Anything else is a real device on the Wi-Fi and is shown
     * as-is, which is what distinguishes one phone at the table from another.
     */
    private String actor() {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        return switch (address) {
            case "::1", "0:0:0:0:0:0:0:1", "127.0.0.1" -> "game PC";
            default -> address;
        };
    }

    private String objectiveName(long objectiveId) {
        return catalogue.byId(objectiveId).map(Objective::name)
                .orElse("objective " + objectiveId);
    }

    @GetMapping("/audit")
    public List<AuditEntry> auditTrail(
            @RequestParam(required = false) Long game,
            @RequestParam(defaultValue = "300") int limit) {
        return audit.recent(game, Math.min(Math.max(limit, 1), 2000));
    }

    /** Reference data for the add-player dropdown. */
    @GetMapping("/factions")
    public List<Faction> factions() {
        return factions.all();
    }

    /**
     * Options for the Add-points Label dropdown: the seeded list plus every
     * label already used in a game, so the list grows with use.
     */
    @GetMapping("/point-sources")
    public List<PointSource> pointSources() {
        List<PointSource> seeded = pointSources.all();
        Set<String> known = seeded.stream()
                .map(s -> s.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));

        List<PointSource> result = new ArrayList<>();
        for (PointSource s : seeded) {
            result.add(withImage(s));
        }
        for (String kind : List.of("secret", "other")) {
            for (String label : games.usedLabels(kind)) {
                if (known.add(label.toLowerCase(Locale.ROOT))) {
                    result.add(withImage(
                            new PointSource(kind, 1, label, "Used before", null, true)));
                }
            }
        }
        return result;
    }

    /** Same naming-convention lookup the public objectives use. */
    private PointSource withImage(PointSource s) {
        if (s.image() != null) {
            return s;
        }
        String found = images.resolve(s.name());
        return found == null ? s : new PointSource(
                s.kind(), s.points(), s.name(), s.group(), found, s.learned());
    }

    /**
     * Fills in an image found by naming convention. A stored value always wins,
     * so an explicit filename can override what is on disk.
     */
    private Objective withImage(Objective o) {
        if (o.image() != null) {
            return o;
        }
        String found = images.resolve(o.name());
        return found == null ? o : new Objective(
                o.id(), o.name(), o.stage(), o.points(), o.requirement(), o.expansion(), found);
    }

    private RevealedObjective withImage(RevealedObjective o) {
        if (o.image() != null) {
            return o;
        }
        String found = images.resolve(o.name());
        return found == null ? o : new RevealedObjective(
                o.id(), o.name(), o.stage(), o.points(), o.requirement(), found,
                o.round(), o.scoredBy());
    }

    // --- games ---------------------------------------------------------------

    @GetMapping("/games")
    public List<Game> listGames() {
        return games.allGames();
    }

    @PostMapping("/games")
    public Map<String, Object> createGame(
            @RequestParam String name,
            @RequestParam(defaultValue = "10") int vpTarget,
            @RequestParam(defaultValue = "3") int maxSecrets) {
        long id = games.createGame(name, vpTarget, maxSecrets);
        log(id, "game.create", "Created game '" + name + "' to " + vpTarget + " VP");
        return Map.of("id", id);
    }

    // --- the one read the UI actually uses -----------------------------------

    @GetMapping("/state")
    public GameState state(@RequestParam("game") long gameId) {
        Game game = games.findGame(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No game with id " + gameId));

        Map<Long, Integer> totals = games.totals(gameId);
        Map<Long, Integer> secrets = games.secretCounts(gameId);

        List<PlayerState> players = games.players(gameId).stream()
                .map(p -> toPlayerState(p, totals, secrets))
                .toList();

        List<RevealedObjective> revealed = games.revealed(gameId).stream()
                .map(this::withImage)
                .toList();
        List<LedgerRow> ledger = games.ledger(gameId);

        return new GameState(game, players, revealed, ledger);
    }

    private static PlayerState toPlayerState(
            Player p, Map<Long, Integer> totals, Map<Long, Integer> secrets) {
        return new PlayerState(
                p.id(),
                p.name(),
                p.faction(),
                p.color(),
                totals.getOrDefault(p.id(), 0),
                secrets.getOrDefault(p.id(), 0));
    }

    // --- end-of-game report --------------------------------------------------

    /**
     * Standings with each total broken down by where the points came from.
     *
     * <p>Read-only: this reports the game, it does not end it. Nothing is
     * written, so it can be opened mid-game as a scoreboard and again at the end
     * without any of the "are you sure" weight that a state change would need.
     */
    @GetMapping("/standings")
    public Standings standings(@RequestParam("game") long gameId) {
        Game game = games.findGame(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No game with id " + gameId));

        List<Player> players = games.players(gameId);
        List<LedgerRow> ledger = games.ledger(gameId);

        // Ledger rows come back in id order, so first-scored decides the column
        // order -- the same sequence the table saw the sources appear in.
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
                Instant.now().toString(),
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

    // --- catalogue -----------------------------------------------------------

    @GetMapping("/objectives")
    public List<Objective> objectives(@RequestParam(required = false) String expansion) {
        List<Objective> found = expansion == null || expansion.isBlank()
                ? catalogue.all()
                : catalogue.byExpansion(expansion);
        return found.stream().map(this::withImage).toList();
    }

    @PostMapping("/objectives")
    public Map<String, Object> addObjective(
            @RequestParam String name,
            @RequestParam String stage,
            @RequestParam int points,
            @RequestParam(required = false) String requirement,
            @RequestParam(defaultValue = "homebrew") String expansion,
            @RequestParam(required = false) String image) {

        if (!stage.equals("I") && !stage.equals("II")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "stage must be I or II, got: " + stage);
        }
        if (catalogue.byName(name).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "An objective named '" + name + "' already exists");
        }
        long id = catalogue.insert(name, stage, points, requirement, expansion, image);
        log(null, "objective.create", "Added card '" + name + "' (stage " + stage
                + ", " + points + " VP, " + expansion + ")");
        return Map.of("id", id);
    }

    @PostMapping("/objectives/image")
    public Map<String, Object> setImage(@RequestParam long id, @RequestParam String image) {
        catalogue.setImage(id, image);
        log(null, "objective.image", "Set image for " + objectiveName(id) + " to " + image);
        return Map.of("ok", true);
    }

    // --- players -------------------------------------------------------------

    /**
     * Adds a player. Colours must be unique within a game.
     *
     * <p>The UI also greys out taken colours, but this check is the one that
     * counts: two people adding players from different phones would not see each
     * other's choice until they refreshed.
     */
    @PostMapping("/players")
    public Map<String, Object> addPlayer(
            @RequestParam long gameId,
            @RequestParam String name,
            @RequestParam(required = false) String faction,
            @RequestParam(required = false) String color) {

        if (color != null && !color.isBlank()
                && games.usedColors(gameId).stream().anyMatch(color::equalsIgnoreCase)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another player is already " + color + " in this game.");
        }
        long id = games.addPlayer(gameId, name, faction, color);
        log(gameId, "player.add", "Added " + name
                + (faction != null && !faction.isBlank() ? " (" + faction + ")" : "")
                + (color != null && !color.isBlank() ? " in " + color : ""));
        return Map.of("id", id);
    }

    /**
     * Removes a player and every score they held.
     *
     * <p>Unlike un-revealing an objective, this is allowed even with points on
     * the board: the player disappears along with their points, so nothing is
     * left showing a total that no longer adds up. The UI names the count before
     * asking.
     */
    @DeleteMapping("/players")
    public Map<String, Object> removePlayer(@RequestParam long id) {
        int scores = games.countScoresForPlayer(id);
        var player = games.findPlayer(id);
        games.removePlayer(id);
        log(player.map(Player::gameId).orElse(null), "player.remove",
                "Removed " + player.map(Player::name).orElse("player " + id)
                        + ", discarding " + scores + " score row(s)");
        return Map.of("ok", true, "scoresRemoved", scores);
    }

    // --- revealing -----------------------------------------------------------

    @PostMapping("/reveal")
    public Map<String, Object> reveal(
            @RequestParam long gameId,
            @RequestParam long objectiveId,
            @RequestParam(required = false) Integer round) {
        games.reveal(gameId, objectiveId, round);
        log(gameId, "objective.reveal", "Revealed " + objectiveName(objectiveId)
                + (round != null ? " in round " + round : ""));
        return Map.of("ok", true);
    }

    /**
     * Takes an objective back off the board.
     *
     * <p>Refused while any player has scored it. Removing it would drop those
     * points out of their totals with nothing on screen to explain the change,
     * and a scoring tracker that quietly loses points is worse than one that
     * makes you undo them deliberately.
     */
    @PostMapping("/unreveal")
    public Map<String, Object> unreveal(
            @RequestParam long gameId,
            @RequestParam long objectiveId) {

        int scored = games.countScoresFor(gameId, objectiveId);
        if (scored > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    scored == 1
                            ? "1 player has scored this objective. Unscore them first, then remove it."
                            : scored + " players have scored this objective. "
                                    + "Unscore them first, then remove it.");
        }
        games.unreveal(gameId, objectiveId);
        log(gameId, "objective.unreveal",
                "Took " + objectiveName(objectiveId) + " off the board");
        return Map.of("ok", true);
    }

    // --- scoring -------------------------------------------------------------

    @PostMapping("/score")
    public Map<String, Object> score(
            @RequestParam long gameId,
            @RequestParam long playerId,
            @RequestParam long objectiveId) {
        boolean nowScored = games.toggleScore(gameId, playerId, objectiveId);
        String who = games.findPlayer(playerId).map(Player::name).orElse("player " + playerId);
        log(gameId, nowScored ? "score.add" : "score.remove",
                who + (nowScored ? " scored " : " un-scored ") + objectiveName(objectiveId));
        return Map.of("scored", nowScored);
    }

    /**
     * Manual points. Note there is deliberately no cap enforcement here: the UI
     * warns when a player passes the secret limit, but the server will not refuse
     * input mid-game.
     */
    @PostMapping("/points")
    public Map<String, Object> addPoints(
            @RequestParam long gameId,
            @RequestParam long playerId,
            @RequestParam int points,
            @RequestParam(required = false) String label,
            @RequestParam(defaultValue = "other") String kind) {

        if (!List.of("public", "secret", "other").contains(kind)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "kind must be public, secret or other; got: " + kind);
        }
        long id = games.addPoints(gameId, playerId, points, label, kind);
        String who = games.findPlayer(playerId).map(Player::name).orElse("player " + playerId);
        log(gameId, "points.add", who + " +" + points + " " + kind
                + (label != null && !label.isBlank() ? ": " + label : ""));
        return Map.of("id", id);
    }

    @PostMapping("/points/delete")
    public Map<String, Object> deletePoints(@RequestParam long id) {
        // Looked up first: once it is deleted there is nothing left to describe.
        var summary = games.scoreSummary(id);
        games.deleteScore(id);
        log(summary.map(ScoreSummary::gameId).orElse(null), "points.remove",
                summary.map(x -> "Removed " + x.playerName() + " +" + x.points()
                                + " " + x.kind() + ": " + x.what())
                        .orElse("Removed score row " + id));
        return Map.of("ok", true);
    }
}
