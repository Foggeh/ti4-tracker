package ti4.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import ti4.domain.Objective;

/** The card catalogue: every public objective we know about, across expansions. */
@Repository
public class CatalogueRepository {

    private static final String SELECT = """
            SELECT id, name, stage, points, requirement, expansion, image
              FROM objective
            """;

    private final JdbcClient jdbc;

    public CatalogueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Objective> all() {
        return jdbc.sql(SELECT + " ORDER BY stage, name").query(Objective.class).list();
    }

    public List<Objective> byExpansion(String expansion) {
        return jdbc.sql(SELECT + " WHERE expansion = :expansion ORDER BY stage, name")
                .param("expansion", expansion)
                .query(Objective.class)
                .list();
    }

    public Optional<Objective> byId(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(Objective.class)
                .optional();
    }

    public Optional<Objective> byName(String name) {
        return jdbc.sql(SELECT + " WHERE name = :name")
                .param("name", name)
                .query(Objective.class)
                .optional();
    }

    public int count() {
        return jdbc.sql("SELECT COUNT(*) FROM objective").query(Integer.class).single();
    }

    public long insert(String name, String stage, int points, String requirement,
                       String expansion, String image) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO objective (name, stage, points, requirement, expansion, image)
                        VALUES (:name, :stage, :points, :requirement, :expansion, :image)
                        """)
                .param("name", name)
                .param("stage", stage)
                .param("points", points)
                .param("requirement", requirement)
                .param("expansion", expansion)
                .param("image", image)
                .update(keys);
        return keys.getKey().longValue();
    }

    /** Attach or replace a card scan without touching the rest of the row. */
    public void setImage(long id, String image) {
        jdbc.sql("UPDATE objective SET image = :image WHERE id = :id")
                .param("image", image)
                .param("id", id)
                .update();
    }
}
