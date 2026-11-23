-- Preserve every legacy event without inventing historical projection payloads.
-- This archive is never read by the new outbox repository/relay.
ALTER TABLE order_outbox RENAME TO order_outbox_legacy;
COMMENT ON TABLE order_outbox_legacy IS 'Read-only archive of events predating projection payloads; retain until an explicit retention decision.';

CREATE TABLE projection_revision (
    event_type VARCHAR(64) NOT NULL,
    projection_key VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    PRIMARY KEY (event_type, projection_key)
);

CREATE TABLE order_outbox (
    id UUID CONSTRAINT pk_projection_outbox PRIMARY KEY,
    order_id UUID REFERENCES orders(id) ON DELETE RESTRICT,
    data_criacao TIMESTAMPTZ NOT NULL,
    data_envio TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDED', 'ERRO')),
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN (
        'CUSTOMER_SUMMARY', 'CUSTOMER_DETAIL', 'PRODUCT_CATALOG', 'PRODUCT_DETAIL',
        'ORDER_SUMMARY', 'ORDER_DETAIL', 'CUSTOMER_ORDER_HISTORY', 'SALES_DASHBOARD'
    )),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    projection_key VARCHAR(128) NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    CONSTRAINT uq_projection_outbox_revision UNIQUE(event_type, projection_key, projection_version),
    CONSTRAINT ck_projection_outbox_delete CHECK (operation <> 'DELETE' OR payload = '{}'::jsonb),
    CONSTRAINT ck_projection_outbox_order CHECK (
        (event_type IN ('ORDER_SUMMARY', 'ORDER_DETAIL', 'CUSTOMER_ORDER_HISTORY')
            AND order_id IS NOT NULL AND projection_key = order_id::text)
        OR (event_type NOT IN ('ORDER_SUMMARY', 'ORDER_DETAIL', 'CUSTOMER_ORDER_HISTORY') AND order_id IS NULL)
    ),
    CONSTRAINT ck_projection_outbox_sent_date CHECK (
        (status = 'SENDED' AND data_envio IS NOT NULL AND data_envio >= data_criacao)
        OR (status IN ('PENDING', 'ERRO') AND data_envio IS NULL)
    )
);
CREATE INDEX ix_projection_outbox_order ON order_outbox(order_id, data_criacao, id);
CREATE INDEX ix_projection_outbox_pending ON order_outbox(data_criacao, id) WHERE status IN ('PENDING', 'ERRO');
