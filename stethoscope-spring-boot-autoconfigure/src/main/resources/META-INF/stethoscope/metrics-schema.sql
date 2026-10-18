-- Stethoscope metrics (the dashboard's overview). Runs at startup unless
-- stethoscope.metrics.initialize-schema=false; with Flyway or Liquibase, copy this into a migration of
-- your own and switch that off. Tested on PostgreSQL and H2; plain enough for MySQL/MariaDB.
CREATE TABLE IF NOT EXISTS stethoscope_metrics (
    bucket_seconds INTEGER       NOT NULL,
    bucket_start   BIGINT        NOT NULL,
    metric         VARCHAR(32)   NOT NULL,
    key_hash       CHAR(40)      NOT NULL,
    metric_key     VARCHAR(1000) NOT NULL,
    hits           BIGINT        NOT NULL,
    total          BIGINT        NOT NULL,
    peak           BIGINT        NOT NULL,
    last_seen      BIGINT        NOT NULL,
    sample         VARCHAR(1000),
    PRIMARY KEY (bucket_seconds, bucket_start, metric, key_hash)
)
