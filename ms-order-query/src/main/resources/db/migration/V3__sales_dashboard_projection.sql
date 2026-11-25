CREATE TABLE sales_dashboard_projection (
    dashboard_id BINARY(16) NOT NULL,
    period_code VARCHAR(24) NOT NULL,
    payload JSON NOT NULL,
    source_observed_at DATETIME(6) NOT NULL,
    computed_at DATETIME(6) NOT NULL,
    projection_version BIGINT NOT NULL CHECK (projection_version>0),
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dashboard_id),
    UNIQUE KEY uq_dashboard_period (period_code),
    CHECK (period_code IN ('TODAY','LAST_7_DAYS','LAST_30_DAYS','MONTH_TO_DATE','PREVIOUS_MONTH')),
    CHECK (computed_at>=source_observed_at)
) ENGINE=InnoDB;
