-- Commands append requests; only dashboard workers/dispatchers lock the five control rows.
CREATE TABLE dashboard_refresh_request (
    id UUID PRIMARY KEY,
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('ORDER_CHANGE','TIMER','REBUILD')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX ix_dashboard_refresh_pending ON dashboard_refresh_request(requested_at,id) WHERE processed_at IS NULL;

CREATE TABLE dashboard_refresh_control (
    dashboard_id UUID PRIMARY KEY,
    period_code VARCHAR(24) NOT NULL UNIQUE CHECK (period_code IN ('TODAY','LAST_7_DAYS','LAST_30_DAYS','MONTH_TO_DATE','PREVIOUS_MONTH')),
    requested_generation BIGINT NOT NULL DEFAULT 1,
    completed_generation BIGINT NOT NULL DEFAULT 0,
    pending_since TIMESTAMPTZ DEFAULT clock_timestamp(),
    next_attempt_at TIMESTAMPTZ DEFAULT clock_timestamp(),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    last_error VARCHAR(256),
    last_source_at TIMESTAMPTZ,
    CHECK (completed_generation>=0 AND requested_generation>=completed_generation)
);
INSERT INTO dashboard_refresh_control(dashboard_id,period_code) VALUES
 ('8b99f328-cb8c-4e22-8aa6-c04fe81cb001','TODAY'),
 ('8b99f328-cb8c-4e22-8aa6-c04fe81cb002','LAST_7_DAYS'),
 ('8b99f328-cb8c-4e22-8aa6-c04fe81cb003','LAST_30_DAYS'),
 ('8b99f328-cb8c-4e22-8aa6-c04fe81cb004','MONTH_TO_DATE'),
 ('8b99f328-cb8c-4e22-8aa6-c04fe81cb005','PREVIOUS_MONTH');

CREATE INDEX ix_dashboard_orders_created ON orders(created_at,id);
CREATE INDEX ix_dashboard_active_customer_created ON orders(customer_id,created_at,id) WHERE status='CREATED';
