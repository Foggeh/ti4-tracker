package ti4.repo;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ti4.domain.AuditEntry;

/**
 * Records what happened, so a change nobody remembers making can be traced.
 *
 * <p>Writes also go to the application log, which means the history survives even
 * if the database file is replaced.
 */
@Repository
public class AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Never throws. An audit write failing must not take down the action it was
     * describing -- losing the note is bad, losing someone's score is worse.
     */
    public void record(Long gameId, String action, String detail, String actor) {
        log.info("audit game={} {} :: {} (from {})", gameId, action, detail, actor);
        try {
            jdbc.sql("""
                            INSERT INTO audit (game_id, action, detail, actor, created_at)
                            VALUES (:gameId, :action, :detail, :actor, :createdAt)
                            """)
                    .param("gameId", gameId)
                    .param("action", action)
                    .param("detail", detail)
                    .param("actor", actor)
                    .param("createdAt", Instant.now().toString())
                    .update();
        } catch (RuntimeException e) {
            log.warn("Could not write audit row for {}: {}", action, e.getMessage());
        }
    }

    /** Newest first. A null gameId returns everything, across games. */
    public List<AuditEntry> recent(Long gameId, int limit) {
        String where = gameId == null ? "" : " WHERE game_id = :gameId";
        var spec = jdbc.sql("""
                        SELECT id, game_id, action, detail, actor, created_at
                          FROM audit
                        """ + where + """
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("limit", limit);
        if (gameId != null) {
            spec = spec.param("gameId", gameId);
        }
        return spec.query(AuditEntry.class).list();
    }
}
