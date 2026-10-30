-- Version 1 of the schema. Flyway records applied versions in flyway_schema_history;
-- later changes go in new V2__..., V3__... files and this file is never edited.

CREATE TABLE IF NOT EXISTS ev6_state (
    tenant VARCHAR(80) PRIMARY KEY,
    revision BIGINT NOT NULL,
    active VARCHAR(36),
    status VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS ev6_datasets (
    tenant VARCHAR(80),
    revision BIGINT,
    body JSONB NOT NULL,
    PRIMARY KEY (tenant, revision),
    FOREIGN KEY (tenant) REFERENCES ev6_state(tenant)
);

CREATE TABLE IF NOT EXISTS ev6_plans (
    id VARCHAR(36) PRIMARY KEY,
    tenant VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    parent VARCHAR(36),
    body JSONB NOT NULL,
    created BIGINT NOT NULL,
    UNIQUE (tenant, id),
    FOREIGN KEY (tenant, revision) REFERENCES ev6_datasets(tenant, revision),
    FOREIGN KEY (tenant, parent) REFERENCES ev6_plans(tenant, id)
);

CREATE TABLE IF NOT EXISTS ev6_jobs (
    id VARCHAR(36) PRIMARY KEY,
    tenant VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    parent VARCHAR(36),
    kind VARCHAR(16),
    status VARCHAR(20),
    generation BIGINT,
    lease_until BIGINT,
    created BIGINT,
    budget_s DOUBLE PRECISION,
    result VARCHAR(36),
    error VARCHAR(100),
    UNIQUE (tenant, id),
    FOREIGN KEY (tenant, revision) REFERENCES ev6_datasets(tenant, revision),
    FOREIGN KEY (tenant, parent) REFERENCES ev6_plans(tenant, id),
    FOREIGN KEY (tenant, result) REFERENCES ev6_plans(tenant, id)
);

CREATE INDEX IF NOT EXISTS ev6_jobs_queue ON ev6_jobs(status, created);

CREATE TABLE IF NOT EXISTS ev6_events (
    tenant VARCHAR(80),
    id VARCHAR(100),
    asset VARCHAR(160),
    sequence BIGINT,
    at BIGINT,
    hash VARCHAR(64),
    status VARCHAR(24),
    job VARCHAR(36),
    body JSONB,
    error VARCHAR(100),
    PRIMARY KEY (tenant, id),
    FOREIGN KEY (tenant, job) REFERENCES ev6_jobs(tenant, id)
);

CREATE INDEX IF NOT EXISTS ev6_events_asset ON ev6_events(tenant, asset, sequence);

CREATE TABLE IF NOT EXISTS ev6_requests (
    tenant VARCHAR(80),
    key VARCHAR(100),
    hash VARCHAR(64),
    job VARCHAR(36),
    PRIMARY KEY (tenant, key),
    FOREIGN KEY (tenant, job) REFERENCES ev6_jobs(tenant, id)
);
