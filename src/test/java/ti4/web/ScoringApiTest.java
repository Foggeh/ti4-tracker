package ti4.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The scoring rules, driven through the API against a real SQLite file.
 *
 * <p>Assertions are on the JSON itself rather than on deserialised objects,
 * because the JSON is the contract: the browser is the only consumer, and a
 * field quietly renamed would break it while any Java-side assertion still
 * passed.
 *
 * <p>The database is a throwaway in a temp directory, never {@code data/ti4.db}
 * — these tests delete players and their scores, which is not something to do
 * to a real game. Only the URL is overridden, so the schema, the seeding and
 * the foreign-key PRAGMA are all the application's own configuration and are
 * under test too.
 *
 * <p>Each test makes its own game. Games are independent, so that is enough
 * isolation without truncating tables in between.
 */
@SpringBootTest
class ScoringApiTest {

    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void useAThrowawayDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + DATABASE.toString().replace('\\', '/'));
    }

    private static Path temporaryDatabase() {
        try {
            Path dir = Files.createTempDirectory("ti4-test");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve("test.db");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc}: Spring Boot
     * 4 moved that annotation out of spring-boot-starter-test, and MockMvc from
     * spring-test needs no extra dependency.
     */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // --- helpers -------------------------------------------------------------

    private ResultActions send(String path, String... keysAndValues) throws Exception {
        MockHttpServletRequestBuilder request = post(path);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            request = request.param(keysAndValues[i], keysAndValues[i + 1]);
        }
        return mvc.perform(request);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static int status(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private String fetch(String path) throws Exception {
        return body(mvc.perform(get(path)));
    }

    private long idOf(ResultActions result) throws Exception {
        assertThat(status(result))
                .as("expected success, body was: %s", body(result))
                .isBetween(200, 299);
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private long newGame(String name) throws Exception {
        return idOf(send("/api/games", "name", name, "vpTarget", "10", "maxSecrets", "3"));
    }

    private long addPlayer(long game, String name, String color) throws Exception {
        return idOf(send("/api/players",
                "gameId", String.valueOf(game), "name", name, "color", color));
    }

    private int totalFor(long game, String name) throws Exception {
        List<Integer> totals = JsonPath.read(fetch("/api/state?game=" + game),
                "$.players[?(@.name == '" + name + "')].total");
        assertThat(totals).as("no player named %s in game %d", name, game).hasSize(1);
        return totals.get(0);
    }

    private int ledgerSize(long game) throws Exception {
        return JsonPath.read(fetch("/api/state?game=" + game), "$.ledger.length()");
    }

    private int revealedCount(long game) throws Exception {
        return JsonPath.read(fetch("/api/state?game=" + game), "$.revealed.length()");
    }

    /** The first Stage I card the catalogue seeded, with its printed value. */
    private Map<String, Object> aStageOneCard() throws Exception {
        List<Map<String, Object>> stageOne =
                JsonPath.read(fetch("/api/objectives"), "$[?(@.stage == 'I')]");
        assertThat(stageOne).as("the catalogue seeded no Stage I cards").isNotEmpty();
        return stageOne.get(0);
    }

    private static long id(Map<String, Object> card) {
        return ((Number) card.get("id")).longValue();
    }

    private static int points(Map<String, Object> card) {
        return ((Number) card.get("points")).intValue();
    }

    // --- tests ---------------------------------------------------------------

    @Test
    @DisplayName("scoring a public objective moves the total and the standings together")
    void scoringMovesTotalAndStandings() throws Exception {
        long game = newGame("scoring");
        long ana = addPlayer(game, "Ana", "blue");
        Map<String, Object> card = aStageOneCard();

        send("/api/reveal", "gameId", String.valueOf(game),
                "objectiveId", String.valueOf(id(card)), "round", "1");
        send("/api/score", "gameId", String.valueOf(game),
                "playerId", String.valueOf(ana), "objectiveId", String.valueOf(id(card)));

        assertThat(totalFor(game, "Ana")).isEqualTo(points(card));

        String standings = fetch("/api/standings?game=" + game);
        assertThat((int) (Integer) JsonPath.read(standings, "$.rows[0].publicPoints"))
                .isEqualTo(points(card));
        assertThat((int) (Integer) JsonPath.read(standings, "$.rows[0].total"))
                .isEqualTo(totalFor(game, "Ana"));
    }

    @Test
    @DisplayName("scoring the same objective twice takes the claim back off")
    void scoringIsAToggle() throws Exception {
        long game = newGame("toggle");
        long ana = addPlayer(game, "Ana", "blue");
        Map<String, Object> card = aStageOneCard();

        send("/api/reveal", "gameId", String.valueOf(game),
                "objectiveId", String.valueOf(id(card)));
        send("/api/score", "gameId", String.valueOf(game),
                "playerId", String.valueOf(ana), "objectiveId", String.valueOf(id(card)));
        assertThat(totalFor(game, "Ana")).isEqualTo(points(card));

        send("/api/score", "gameId", String.valueOf(game),
                "playerId", String.valueOf(ana), "objectiveId", String.valueOf(id(card)));
        assertThat(totalFor(game, "Ana")).isZero();
        assertThat(ledgerSize(game)).isZero();
    }

    @Test
    @DisplayName("an objective someone has scored cannot be taken off the board")
    void refusesToUnrevealAScoredObjective() throws Exception {
        long game = newGame("unreveal guard");
        long ana = addPlayer(game, "Ana", "blue");
        Map<String, Object> card = aStageOneCard();

        send("/api/reveal", "gameId", String.valueOf(game),
                "objectiveId", String.valueOf(id(card)));
        send("/api/score", "gameId", String.valueOf(game),
                "playerId", String.valueOf(ana), "objectiveId", String.valueOf(id(card)));

        ResultActions refused = send("/api/unreveal",
                "gameId", String.valueOf(game), "objectiveId", String.valueOf(id(card)));

        assertThat(status(refused)).isEqualTo(409);
        // Those points are the reason for the refusal, so they must survive it.
        assertThat(totalFor(game, "Ana")).isEqualTo(points(card));
        assertThat(revealedCount(game)).isEqualTo(1);
    }

    @Test
    @DisplayName("an untouched objective can be taken back off the board")
    void allowsUnrevealingAnUntouchedObjective() throws Exception {
        long game = newGame("unreveal clean");
        addPlayer(game, "Ana", "blue");
        Map<String, Object> card = aStageOneCard();

        send("/api/reveal", "gameId", String.valueOf(game),
                "objectiveId", String.valueOf(id(card)));
        ResultActions removed = send("/api/unreveal",
                "gameId", String.valueOf(game), "objectiveId", String.valueOf(id(card)));

        assertThat(status(removed)).isEqualTo(200);
        assertThat(revealedCount(game)).isZero();
    }

    @Test
    @DisplayName("two players in one game cannot take the same colour")
    void coloursAreUniqueWithinAGame() throws Exception {
        long game = newGame("colours");
        addPlayer(game, "Ana", "blue");

        // Upper case on purpose: the check must not be case-sensitive.
        ResultActions clash = send("/api/players",
                "gameId", String.valueOf(game), "name", "Bo", "color", "BLUE");

        assertThat(status(clash)).isEqualTo(409);
        assertThat((int) (Integer) JsonPath.read(
                fetch("/api/state?game=" + game), "$.players.length()")).isEqualTo(1);

        // The same colour in a different game is nobody's business.
        long elsewhere = newGame("colours elsewhere");
        assertThat(status(send("/api/players", "gameId", String.valueOf(elsewhere),
                "name", "Bo", "color", "blue"))).isEqualTo(200);
    }

    @Test
    @DisplayName("removing a player takes their score rows with them")
    void removingAPlayerCascadesToTheirScores() throws Exception {
        long game = newGame("cascade");
        long ana = addPlayer(game, "Ana", "blue");
        long bo = addPlayer(game, "Bo", "red");
        Map<String, Object> card = aStageOneCard();

        send("/api/reveal", "gameId", String.valueOf(game),
                "objectiveId", String.valueOf(id(card)));
        send("/api/score", "gameId", String.valueOf(game),
                "playerId", String.valueOf(ana), "objectiveId", String.valueOf(id(card)));
        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Custodians token", "kind", "other");
        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(bo),
                "points", "1", "label", "Imperial Rider", "kind", "other");

        assertThat(ledgerSize(game)).isEqualTo(3);

        mvc.perform(delete("/api/players").param("id", String.valueOf(ana)));

        // Without PRAGMA foreign_keys = ON, SQLite ignores the cascade. The
        // ledger reads from score without joining player, so Ana's rows would
        // survive here as orphans and still be counted.
        assertThat(ledgerSize(game)).isEqualTo(1);
        assertThat(((Number) JsonPath.read(fetch("/api/state?game=" + game),
                "$.ledger[0].playerId")).longValue()).isEqualTo(bo);
        assertThat(totalFor(game, "Bo")).isEqualTo(1);
    }

    @Test
    @DisplayName("manual points land in the right bucket and sum per source")
    void manualPointsAreBucketedAndSummed() throws Exception {
        long game = newGame("manual");
        long ana = addPlayer(game, "Ana", "blue");

        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Become a Martyr", "kind", "secret");
        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Support for the Throne", "kind", "other");
        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Support for the Throne", "kind", "other");

        String standings = fetch("/api/standings?game=" + game);
        assertThat((int) (Integer) JsonPath.read(standings, "$.rows[0].secretPoints")).isEqualTo(1);
        assertThat((int) (Integer) JsonPath.read(standings, "$.rows[0].secretCount")).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(standings, "$.otherColumns"))
                .containsExactly("Support for the Throne");
        assertThat((int) (Integer) JsonPath.read(
                standings, "$.rows[0].bySource['Support for the Throne']")).isEqualTo(2);
        assertThat((int) (Integer) JsonPath.read(standings, "$.rows[0].total")).isEqualTo(3);
    }

    @Test
    @DisplayName("a source nobody scored gets no column")
    void unscoredSourcesGetNoColumn() throws Exception {
        long game = newGame("columns");
        long ana = addPlayer(game, "Ana", "blue");

        send("/api/points", "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Custodians token", "kind", "other");

        assertThat((List<String>) JsonPath.read(
                fetch("/api/standings?game=" + game), "$.otherColumns"))
                .containsExactly("Custodians token")
                .doesNotContain("Shard of the Throne");
    }

    @Test
    @DisplayName("rejects an unknown kind rather than storing it")
    void rejectsAnUnknownKind() throws Exception {
        long game = newGame("bad kind");
        long ana = addPlayer(game, "Ana", "blue");

        ResultActions bad = send("/api/points",
                "gameId", String.valueOf(game), "playerId", String.valueOf(ana),
                "points", "1", "label", "Something", "kind", "nonsense");

        assertThat(status(bad)).isEqualTo(400);
        assertThat(ledgerSize(game)).isZero();
    }

    @Test
    @DisplayName("asking about a game that does not exist is a 404, not an empty report")
    void unknownGameIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/api/standings?game=999999"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/state?game=999999"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("the catalogue seeds itself from the CSV on a fresh database")
    void catalogueSeedsFromCsv() throws Exception {
        String objectives = fetch("/api/objectives");

        assertThat((int) (Integer) JsonPath.read(objectives, "$.length()")).isEqualTo(40);
        assertThat((List<Object>) JsonPath.read(objectives, "$[?(@.stage == 'I')]")).hasSize(20);
        assertThat((List<Object>) JsonPath.read(objectives, "$[?(@.stage == 'II')]")).hasSize(20);
    }
}
