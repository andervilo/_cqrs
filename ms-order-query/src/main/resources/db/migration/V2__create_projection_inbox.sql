CREATE TABLE inbox_event (
    consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id BINARY(16) NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK (operation IN ('UPSERT','DELETE')),
    content_hash BINARY(32) NOT NULL,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('APPLIED','IGNORED_STALE')),
    occurred_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    PRIMARY KEY (consumer_name,event_id),
    UNIQUE KEY uq_inbox_revision (consumer_name,event_type,projection_key,projection_version)
) ENGINE=InnoDB;

CREATE TABLE projection_state (
    consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_version BIGINT NOT NULL DEFAULT 0 CHECK (applied_version >= 0),
    last_event_id BINARY(16),
    last_content_hash BINARY(32),
    last_operation VARCHAR(16) CHECK (last_operation IN ('UPSERT','DELETE')),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name,event_type,projection_key)
) ENGINE=InnoDB;
CREATE INDEX ix_inbox_processed ON inbox_event(processed_at);
