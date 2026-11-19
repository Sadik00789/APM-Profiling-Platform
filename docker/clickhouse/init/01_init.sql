-- 1. Distributed Traces & Spans Table
CREATE TABLE IF NOT EXISTS default.traces_spans (
    trace_id String CODEC(ZSTD(1)),
    span_id String CODEC(ZSTD(1)),
    parent_span_id String CODEC(ZSTD(1)),
    service_name LowCardinality(String) CODEC(ZSTD(1)),
    operation_name LowCardinality(String) CODEC(ZSTD(1)),
    span_kind LowCardinality(String) CODEC(ZSTD(1)),
    start_time DateTime64(6, 'UTC') CODEC(DoubleDelta, ZSTD(3)),
    end_time DateTime64(6, 'UTC') CODEC(DoubleDelta, ZSTD(3)),
    duration_nano UInt64 CODEC(T64, ZSTD(3)),
    status_code LowCardinality(String) CODEC(ZSTD(1)),
    status_message String CODEC(ZSTD(1)),
    attributes Map(String, String) CODEC(ZSTD(3)),
    events Array(Tuple(DateTime64(6, 'UTC'), String, Map(String, String))),
    INDEX idx_trace_id trace_id TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_span_id span_id TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_duration duration_nano TYPE minmax GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(start_time)
ORDER BY (service_name, start_time, trace_id, span_id)
TTL toDateTime(start_time) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1;

-- 2. Continuous Profiling Call Stacks Table
CREATE TABLE IF NOT EXISTS default.profiles_samples (
    profile_id UUID DEFAULT generateUUIDv4(),
    service_name LowCardinality(String) CODEC(ZSTD(1)),
    profile_type LowCardinality(String) CODEC(ZSTD(1)),
    sample_timestamp DateTime64(6, 'UTC') CODEC(DoubleDelta, ZSTD(3)),
    duration_nano UInt64 CODEC(T64, ZSTD(3)),
    value Int64 CODEC(T64, ZSTD(3)),
    unit LowCardinality(String) CODEC(ZSTD(1)),
    stack_trace String CODEC(ZSTD(6)),
    labels Map(String, String) CODEC(ZSTD(3)),
    INDEX idx_service service_name TYPE set(100) GRANULARITY 1,
    INDEX idx_profile_type profile_type TYPE set(10) GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(sample_timestamp)
ORDER BY (service_name, profile_type, sample_timestamp, stack_trace)
TTL toDateTime(sample_timestamp) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192;

-- 3. 1-Minute Service Latency Rollup & Materialized View
CREATE TABLE IF NOT EXISTS default.service_latency_1m (
    minute_time DateTime('UTC') CODEC(DoubleDelta, ZSTD(3)),
    service_name LowCardinality(String) CODEC(ZSTD(1)),
    operation_name LowCardinality(String) CODEC(ZSTD(1)),
    total_spans SimpleAggregateFunction(sum, UInt64) CODEC(T64, ZSTD(3)),
    error_spans SimpleAggregateFunction(sum, UInt64) CODEC(T64, ZSTD(3)),
    duration_quantiles AggregateFunction(quantilesExact(0.50, 0.95, 0.99), UInt64)
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMMDD(minute_time)
ORDER BY (service_name, operation_name, minute_time)
TTL minute_time + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_service_latency_1m
TO default.service_latency_1m AS
SELECT
    toStartOfMinute(toDateTime(start_time)) AS minute_time,
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
