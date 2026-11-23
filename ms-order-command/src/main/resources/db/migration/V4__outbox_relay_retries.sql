ALTER TABLE order_outbox
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error VARCHAR(256);

UPDATE order_outbox SET next_attempt_at=NULL WHERE status='SENDED';

ALTER TABLE order_outbox ADD CONSTRAINT ck_outbox_sent_retry
    CHECK (status <> 'SENDED' OR next_attempt_at IS NULL);

CREATE INDEX ix_outbox_relay_due ON order_outbox(next_attempt_at, data_criacao, id)
    WHERE status IN ('PENDING', 'ERRO');
CREATE INDEX ix_outbox_relay_predecessor ON order_outbox(event_type, projection_key, projection_version)
    WHERE status <> 'SENDED';
