CREATE TABLE customer (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL CHECK (btrim(name) <> ''),
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email))),
    phone VARCHAR(24),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    name VARCHAR(160) NOT NULL CHECK (btrim(name) <> ''),
    description VARCHAR(2000),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price > 0 AND unit_price <= 999999999.99),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

-- ORDER is a SQL keyword; use orders for the physical table.
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer(id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CREATED', 'CANCELLED')),
    total_amount NUMERIC(19,2) NOT NULL CHECK (total_amount >= 0),
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_orders_customer_created ON orders(customer_id, created_at DESC, id);
CREATE INDEX ix_orders_status_created ON orders(status, created_at DESC, id);

CREATE TABLE item (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE RESTRICT,
    product_sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(160) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price > 0 AND unit_price <= 999999999.99),
    CONSTRAINT uq_item_order_product UNIQUE(order_id, product_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ix_item_product ON item(product_id);

CREATE TABLE order_outbox (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    data_criacao TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_envio TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDED', 'ERRO')),
    CONSTRAINT ck_outbox_sent_date CHECK (
        (status = 'SENDED' AND data_envio IS NOT NULL AND data_envio >= data_criacao)
        OR (status IN ('PENDING', 'ERRO') AND data_envio IS NULL)
    )
);
CREATE INDEX ix_outbox_order ON order_outbox(order_id, data_criacao, id);
CREATE INDEX ix_outbox_pending ON order_outbox(data_criacao, id) WHERE status IN ('PENDING', 'ERRO');
