-- ClickHouse Profiles Continuous Profiling Schema
CREATE DATABASE IF NOT EXISTS default;

CREATE TABLE IF NOT EXISTS default.profiles_samples
(
    timestamp DateTime64(0, 'UTC') CODEC(DoubleDelta, ZSTD(3)),
    service_name LowCardinality(String) CODEC(ZSTD(1)),
    profile_type LowCardinality(String) CODEC(ZSTD(1)),
    stack_trace String CODEC(ZSTD(6)),
    sample_count SimpleAggregateFunction(sum, UInt64) CODEC(T64, ZSTD(3)),
    INDEX idx_service service_name TYPE set(100) GRANULARITY 1,
    INDEX idx_profile_type profile_type TYPE set(10) GRANULARITY 1
)
ENGINE = AggregatingMergeTree()
PARTITION BY toDate(timestamp)
PRIMARY KEY (service_name, profile_type)
ORDER BY (service_name, profile_type, timestamp, stack_trace)
TTL toDateTime(timestamp) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192;
