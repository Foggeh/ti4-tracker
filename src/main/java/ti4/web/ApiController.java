package ti4.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import ti4.domain.PointSource;
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

    public ApiController(GameRepository games, CatalogueRepository catalogue,
                         ImageResolver images, FactionCatalogue factions,
                         PointSourceCatalogue pointSources) {
        this.games = games;
        this.catalogue = catalogue;
        this.images = images;
        this.factions = factions;
        this.pointSources = pointSources;
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
        return Map.of("id", id);
    }

    @PostMapping("/objectives/image")
    public Map<String, Object> setImage(@RequestParam long id, @RequestParam String image) {
        catalogue.setImage(id, image);
        return Map.of("ok", true);
    }

    // --- players -------------------------------------------------------------

    @PostMapping("/players")
    public Map<String, Object> addPlayer(
            @RequestParam long gameId,
            @RequestParam String name,
            @RequestParam(required = false) String faction,
            @RequestParam(required = false) String color) {
        long id = games.addPlayer(gameId, name, faction, color);
        return Map.of("id", id);
    }

    @DeleteMapping("/players")
    public Map<String, Object> removePlayer(@RequestParam long id) {
        games.removePlayer(id);
        return Map.of("ok", true);
    }

    // --- revealing -----------------------------------------------------------

    @PostMapping("/reveal")
    public Map<String, Object> reveal(
            @RequestParam long gameId,
            @RequestParam long objectiveId,
            @RequestParam(required = false) Integer round) {
        games.reveal(gameId, objectiveId, round);
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
        return Map.of("ok", true);
    }

    // --- scoring -------------------------------------------------------------

    @PostMapping("/score")
    public Map<String, Object> score(
            @RequestParam long gameId,
            @RequestParam long playerId,
            @RequestParam long objectiveId) {
        boolean nowScored = games.toggleScore(gameId, playerId, objectiveId);
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
        return Map.of("id", id);
    }

    @PostMapping("/points/delete")
    public Map<String, Object> deletePoints(@RequestParam long id) {
        games.deleteScore(id);
        return Map.of("ok", true);
    }
}
