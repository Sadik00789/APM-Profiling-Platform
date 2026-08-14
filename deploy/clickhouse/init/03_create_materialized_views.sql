-- 1-Minute Service Metrics Aggregate Table
CREATE TABLE IF NOT EXISTS default.service_latency_1m
(
    minute_time DateTime('UTC') CODEC(DoubleDelta, ZSTD(3)),
    service_name LowCardinality(String) CODEC(ZSTD(1)),
    operation_name LowCardinality(String) CODEC(ZSTD(1)),
    total_spans UInt64 CODEC(T64, ZSTD(3)),
    error_spans UInt64 CODEC(T64, ZSTD(3)),
    duration_quantiles AggregateFunction(quantilesExact(0.50, 0.95, 0.99), UInt64)
)
ENGINE = AggregatingMergeTree()
PARTITION BY toDate(minute_time)
PRIMARY KEY (service_name, operation_name)
ORDER BY (service_name, operation_name, minute_time)
TTL minute_time + INTERVAL 30 DAY;

-- Materialized View feeding 1-Minute Rollup
CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_service_latency_1m
TO default.service_latency_1m AS
SELECT
    toStartOfMinute(toDateTime(timestamp)) AS minute_time,
    service_name,
    operation_name,
    count() AS total_spans,
    countIf(status_code = 'ERROR' OR status_code = 'STATUS_CODE_ERROR') AS error_spans,
    quantilesExactState(0.50, 0.95, 0.99)(duration_nano) AS duration_quantiles
FROM default.traces_spans
GROUP BY
    minute_time,
    service_name,
    operation_name;

-- Service Dependency Edge Matrix Table
CREATE TABLE IF NOT EXISTS default.service_dependencies_1m
(
    minute_time DateTime('UTC') CODEC(DoubleDelta, ZSTD(3)),
    parent_service LowCardinality(String) CODEC(ZSTD(1)),
    child_service LowCardinality(String) CODEC(ZSTD(1)),
    call_count UInt64 CODEC(T64, ZSTD(3)),
    error_count UInt64 CODEC(T64, ZSTD(3)),
    avg_latency_nano UInt64 CODEC(T64, ZSTD(3))
)
ENGINE = SummingMergeTree((call_count, error_count, avg_latency_nano))
PARTITION BY toDate(minute_time)
PRIMARY KEY (parent_service, child_service)
ORDER BY (parent_service, child_service, minute_time)
TTL minute_time + INTERVAL 30 DAY;
