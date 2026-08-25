package ti4.repo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import ti4.domain.Game;
import ti4.domain.LedgerRow;
import ti4.domain.Player;
import ti4.domain.RevealedObjective;

/** Games, their players, which objectives are face-up, and the score ledger. */
@Repository
public class GameRepository {

    private static final String GAME_COLUMNS =
            "id, name, created_at, vp_target, max_secrets, finished";

    private final JdbcClient jdbc;

    public GameRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- games ---------------------------------------------------------------

    public List<Game> allGames() {
        return jdbc.sql("SELECT " + GAME_COLUMNS + " FROM game ORDER BY id DESC")
                .query(Game.class)
                .list();
    }

    public Optional<Game> findGame(long id) {
        return jdbc.sql("SELECT " + GAME_COLUMNS + " FROM game WHERE id = :id")
                .param("id", id)
                .query(Game.class)
                .optional();
    }

    public long createGame(String name, int vpTarget, int maxSecrets) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO game (name, created_at, vp_target, max_secrets)
                        VALUES (:name, :createdAt, :vpTarget, :maxSecrets)
                        """)
                .param("name", name)
                .param("createdAt", Instant.now().toString())
                .param("vpTarget", vpTarget)
                .param("maxSecrets", maxSecrets)
                .update(keys);
        return keys.getKey().longValue();
    }

    // --- players -------------------------------------------------------------

    public List<Player> players(long gameId) {
        return jdbc.sql("""
                        SELECT id, game_id, name, faction, color, seat
                          FROM player
                         WHERE game_id = :gameId
                         ORDER BY COALESCE(seat, id)
                        """)
                .param("gameId", gameId)
                .query(Player.class)
                .list();
    }

    public long addPlayer(long gameId, String name, String faction, String color) {
        Integer nextSeat = jdbc.sql("SELECT COUNT(*) FROM player WHERE game_id = :gameId")
                .param("gameId", gameId)
                .query(Integer.class)
                .single();

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO player (game_id, name, faction, color, seat)
                        VALUES (:gameId, :name, :faction, :color, :seat)
                        """)
                .param("gameId", gameId)
                .param("name", name)
                .param("faction", faction)
                .param("color", color)
                .param("seat", nextSeat)
                .update(keys);
        return keys.getKey().longValue();
    }

    public void removePlayer(long playerId) {
        jdbc.sql("DELETE FROM player WHERE id = :id").param("id", playerId).update();
    }

    // --- revealing objectives ------------------------------------------------

    public void reveal(long gameId, long objectiveId, Integer round) {
        jdbc.sql("""
                        INSERT INTO game_objective (game_id, objective_id, revealed_round)
                        VALUES (:gameId, :objectiveId, :round)
                        ON CONFLICT (game_id, objective_id)
                          DO UPDATE SET revealed_round = :round
                        """)
                .param("gameId", gameId)
                .param("objectiveId", objectiveId)
                .param("round", round)
                .update();
    }

    /** How many players have scored this objective in this game. */
    public int countScoresFor(long gameId, long objectiveId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM score
                         WHERE game_id = :gameId AND objective_id = :objectiveId
                        """)
                .param("gameId", gameId)
                .param("objectiveId", objectiveId)
                .query(Integer.class)
                .single();
    }

    /**
     * Undo a misclick by taking an objective back off the board.
     *
     * <p>Callers must check {@link #countScoresFor} first: an objective with
     * points on it is not removable, because deleting it would silently drop
     * those points from players' totals. Unscore the players first.
     */
    public void unreveal(long gameId, long objectiveId) {
        jdbc.sql("""
                        DELETE FROM game_objective
                         WHERE game_id = :gameId AND objective_id = :objectiveId
                        """)
                .param("gameId", gameId)
                .param("objectiveId", objectiveId)
                .update();
    }

    public List<RevealedObjective> revealed(long gameId) {
        Map<Long, List<Long>> scorers = scorersByObjective(gameId);

        return jdbc.sql("""
                        SELECT o.id, o.name, o.stage, o.points, o.requirement, o.image,
                               go.revealed_round
                          FROM game_objective go
                          JOIN objective o ON o.id = go.objective_id
                         WHERE go.game_id = :gameId
                         ORDER BY o.stage, go.revealed_round, o.name
                        """)
                .param("gameId", gameId)
                .query((rs, rowNum) -> {
                    long id = rs.getLong("id");
                    int round = rs.getInt("revealed_round");
                    boolean roundWasNull = rs.wasNull();
                    return new RevealedObjective(
                            id,
                            rs.getString("name"),
                            rs.getString("stage"),
                            rs.getInt("points"),
                            rs.getString("requirement"),
                            rs.getString("image"),
                            roundWasNull ? null : round,
                            scorers.getOrDefault(id, List.of()));
                })
                .list();
    }

    private Map<Long, List<Long>> scorersByObjective(long gameId) {
        Map<Long, List<Long>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT objective_id, player_id
                          FROM score
                         WHERE game_id = :gameId AND objective_id IS NOT NULL
                        """)
                .param("gameId", gameId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("objective_id"), rs.getLong("player_id")))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return result;
    }

    // --- scoring -------------------------------------------------------------

    /**
     * Flip a claim on a public objective for one player.
     *
     * @return true if they now hold it, false if the claim was removed
     */
    public boolean toggleScore(long gameId, long playerId, long objectiveId) {
        int existing = jdbc.sql("""
                        SELECT COUNT(*) FROM score
                         WHERE game_id = :gameId
                           AND player_id = :playerId
                           AND objective_id = :objectiveId
                        """)
                .param("gameId", gameId)
                .param("playerId", playerId)
                .param("objectiveId", objectiveId)
                .query(Integer.class)
                .single();

        if (existing > 0) {
            jdbc.sql("""
                            DELETE FROM score
                             WHERE game_id = :gameId
                               AND player_id = :playerId
                               AND objective_id = :objectiveId
                            """)
                    .param("gameId", gameId)
                    .param("playerId", playerId)
                    .param("objectiveId", objectiveId)
                    .update();
            return false;
        }

        int points = jdbc.sql("SELECT points FROM objective WHERE id = :id")
                .param("id", objectiveId)
                .query(Integer.class)
                .single();

        jdbc.sql("""
                        INSERT INTO score (game_id, player_id, objective_id, points, label, kind, created_at)
                        VALUES (:gameId, :playerId, :objectiveId, :points, NULL, 'public', :createdAt)
                        """)
                .param("gameId", gameId)
                .param("playerId", playerId)
                .param("objectiveId", objectiveId)
                .param("points", points)
                .param("createdAt", Instant.now().toString())
                .update();
        return true;
    }

    /** Manual entry: secret objectives, custodians, agendas, relics, anything. */
    public long addPoints(long gameId, long playerId, int points, String label, String kind) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO score (game_id, player_id, objective_id, points, label, kind, created_at)
                        VALUES (:gameId, :playerId, NULL, :points, :label, :kind, :createdAt)
                        """)
                .param("gameId", gameId)
                .param("playerId", playerId)
                .param("points", points)
                .param("label", label)
                .param("kind", kind)
                .param("createdAt", Instant.now().toString())
                .update(keys);
        return keys.getKey().longValue();
    }

    public void deleteScore(long id) {
        jdbc.sql("DELETE FROM score WHERE id = :id").param("id", id).update();
    }

    public List<LedgerRow> ledger(long gameId) {
        return jdbc.sql("""
                        SELECT s.id, s.player_id, s.objective_id, o.name AS objective_name,
                               s.points, s.label, s.kind
                          FROM score s
                          LEFT JOIN objective o ON o.id = s.objective_id
                         WHERE s.game_id = :gameId
                         ORDER BY s.id
                        """)
                .param("gameId", gameId)
                .query((rs, rowNum) -> {
                    long objectiveId = rs.getLong("objective_id");
                    boolean objectiveWasNull = rs.wasNull();
                    return new LedgerRow(
                            rs.getLong("id"),
                            rs.getLong("player_id"),
                            objectiveWasNull ? null : objectiveId,
                            rs.getString("objective_name"),
                            rs.getInt("points"),
                            rs.getString("label"),
                            rs.getString("kind"));
                })
                .list();
    }

    /**
     * Labels already used for manual entries of this kind, across every game.
     *
     * <p>This is what makes the Label dropdown self-populating: a card name typed
     * once is offered from then on, so the seed file never has to be complete.
     */
    public List<String> usedLabels(String kind) {
        return jdbc.sql("""
                        SELECT DISTINCT label
                          FROM score
                         WHERE kind = :kind
                           AND label IS NOT NULL
                           AND TRIM(label) <> ''
                         ORDER BY label COLLATE NOCASE
                        """)
                .param("kind", kind)
                .query(String.class)
                .list();
    }

    /** Player id to total VP. Totals are always derived, never stored. */
    public Map<Long, Integer> totals(long gameId) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT player_id, COALESCE(SUM(points), 0) AS total
                          FROM score
                         WHERE game_id = :gameId
                         GROUP BY player_id
                        """)
                .param("gameId", gameId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("player_id"), rs.getInt("total")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** Player id to number of secret objectives claimed, for the cap warning. */
    public Map<Long, Integer> secretCounts(long gameId) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT player_id, COUNT(*) AS n
                          FROM score
                         WHERE game_id = :gameId AND kind = 'secret'
                         GROUP BY player_id
                        """)
                .param("gameId", gameId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("player_id"), rs.getInt("n")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }
}
