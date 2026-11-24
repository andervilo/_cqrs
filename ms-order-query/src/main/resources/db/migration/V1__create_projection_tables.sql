-- Read models are independent snapshots; intentionally no cross-projection foreign keys.
CREATE TABLE customer_summary_projection (
    customer_id BINARY(16) NOT NULL,
    name VARCHAR(160) NOT NULL,
    email VARCHAR(254) NOT NULL,
    order_count BIGINT NOT NULL,
    last_order_at DATETIME(6),
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_id)
) ENGINE=InnoDB;

CREATE TABLE customer_detail_projection (
    customer_id BINARY(16) NOT NULL,
    name VARCHAR(160) NOT NULL,
    email VARCHAR(254) NOT NULL,
    phone VARCHAR(24),
    addresses JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    order_count BIGINT NOT NULL,
    lifetime_value DECIMAL(19,2) NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_id)
) ENGINE=InnoDB;

CREATE TABLE product_catalog_projection (
    product_id BINARY(16) NOT NULL,
    name VARCHAR(160) NOT NULL,
    price DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    thumbnail_url VARCHAR(2048),
    available BOOLEAN NOT NULL,
    category VARCHAR(160),
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (product_id)
) ENGINE=InnoDB;

CREATE TABLE product_detail_projection (
    product_id BINARY(16) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    price DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    image_urls JSON NOT NULL,
    available BOOLEAN NOT NULL,
    attributes JSON NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (product_id)
) ENGINE=InnoDB;

CREATE TABLE order_summary_projection (
    order_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    item_count INTEGER NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;

CREATE TABLE order_detail_projection (
    order_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    items JSON NOT NULL,
    subtotal_amount DECIMAL(19,2) NOT NULL,
    discount_amount DECIMAL(19,2) NOT NULL,
    shipping_amount DECIMAL(19,2) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;

CREATE TABLE customer_order_history_projection (
    order_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    item_count INTEGER NOT NULL,
    first_product_name VARCHAR(160),
    projection_version BIGINT NOT NULL CHECK (projection_version > 0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;

CREATE INDEX ix_customer_summary_name ON customer_summary_projection(name,customer_id);
CREATE INDEX ix_customer_summary_last_order ON customer_summary_projection(last_order_at,customer_id);
CREATE INDEX ix_customer_summary_email ON customer_summary_projection(email,customer_id);
CREATE INDEX ix_product_catalog_name ON product_catalog_projection(name,product_id);
CREATE INDEX ix_product_catalog_available_price ON product_catalog_projection(available,price,product_id);
CREATE INDEX ix_product_catalog_category_price ON product_catalog_projection(category,price,product_id);
CREATE INDEX ix_order_summary_customer_date ON order_summary_projection(customer_id,created_at,order_id);
CREATE INDEX ix_order_summary_status_date ON order_summary_projection(status,created_at,order_id);
CREATE INDEX ix_order_summary_date ON order_summary_projection(created_at,order_id);
CREATE INDEX ix_customer_history_date ON customer_order_history_projection(customer_id,created_at,order_id);
