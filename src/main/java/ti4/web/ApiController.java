package ti4.web;

import java.util.List;
import java.util.Map;

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

    public ApiController(GameRepository games, CatalogueRepository catalogue) {
        this.games = games;
        this.catalogue = catalogue;
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

        List<RevealedObjective> revealed = games.revealed(gameId);
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
        return expansion == null || expansion.isBlank()
                ? catalogue.all()
                : catalogue.byExpansion(expansion);
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

    @PostMapping("/unreveal")
    public Map<String, Object> unreveal(
            @RequestParam long gameId,
            @RequestParam long objectiveId) {
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
