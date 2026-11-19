-- ClickHouse Profiles Continuous Profiling Schema
CREATE DATABASE IF NOT EXISTS default;

CREATE TABLE IF NOT EXISTS default.profiles_samples
(
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
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(sample_timestamp)
PRIMARY KEY (service_name, profile_type)
ORDER BY (service_name, profile_type, sample_timestamp, stack_trace)
TTL toDateTime(sample_timestamp) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192;
