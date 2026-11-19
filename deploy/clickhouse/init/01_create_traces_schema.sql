-- ClickHouse Traces Storage Schema
CREATE DATABASE IF NOT EXISTS default;

CREATE TABLE IF NOT EXISTS default.traces_spans
(
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
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(start_time)
ORDER BY (service_name, start_time, trace_id, span_id)
TTL toDateTime(start_time) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1;
