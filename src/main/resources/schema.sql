-- TI4 tracker schema. Idempotent: safe to run on every boot.

CREATE TABLE IF NOT EXISTS objective (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    stage       TEXT    NOT NULL CHECK (stage IN ('I', 'II')),
    points      INTEGER NOT NULL,
    requirement TEXT,
    expansion   TEXT    NOT NULL,
    image       TEXT
);

CREATE TABLE IF NOT EXISTS game (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    vp_target   INTEGER NOT NULL DEFAULT 10,
    max_secrets INTEGER NOT NULL DEFAULT 3,
    finished    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS player (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id INTEGER NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    name    TEXT    NOT NULL,
    faction TEXT,
    color   TEXT,
    seat    INTEGER
);

-- Which public objectives are face-up in a given game, and when they flipped.
CREATE TABLE IF NOT EXISTS game_objective (
    game_id        INTEGER NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    objective_id   INTEGER NOT NULL REFERENCES objective (id),
    revealed_round INTEGER,
    PRIMARY KEY (game_id, objective_id)
);

-- Append-only ledger. Every point in the game is one row here, so a total is a
-- SUM and a mistake is fixed by deleting a single row.
--
-- objective_id set   -> a public objective was scored
-- objective_id NULL  -> manual entry (secret objective, custodians, agenda,
--                       relic, Support for the Throne, ...) described by label
CREATE TABLE IF NOT EXISTS score (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id      INTEGER NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    player_id    INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    objective_id INTEGER REFERENCES objective (id),
    points       INTEGER NOT NULL,
    label        TEXT,
    kind         TEXT    NOT NULL CHECK (kind IN ('public', 'secret', 'other')),
    created_at   TEXT    NOT NULL,
    -- Stops the same player scoring one public objective twice. SQLite treats
    -- NULLs as distinct, so manual entries are unaffected by this constraint.
    UNIQUE (game_id, player_id, objective_id)
);

-- Append-only history of what happened, so a change nobody remembers making can
-- be traced. Deliberately NOT foreign-keyed to game/player: the whole point is
-- that the record outlives the row it describes, and a cascade would delete the
-- evidence along with the player.
CREATE TABLE IF NOT EXISTS audit (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id    INTEGER,
    action     TEXT NOT NULL,
    detail     TEXT,
    actor      TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_score_game   ON score (game_id);
CREATE INDEX IF NOT EXISTS idx_player_game  ON player (game_id);
CREATE INDEX IF NOT EXISTS idx_audit_game   ON audit (game_id, id DESC);
