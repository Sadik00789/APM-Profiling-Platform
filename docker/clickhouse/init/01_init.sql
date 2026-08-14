-- 1. Distributed Traces & Spans Table
CREATE TABLE IF NOT EXISTS default.traces_spans (
    trace_id String,
    span_id String,
    parent_span_id String,
    service_name LowCardinality(String),
    operation_name LowCardinality(String),
    span_kind LowCardinality(String),
    start_time DateTime64(6, 'UTC'),
    end_time DateTime64(6, 'UTC'),
    duration_nano UInt64,
    status_code LowCardinality(String),
    status_message String,
    attributes Map(String, String) CODEC(ZSTD(3)),
    events Array(Tuple(DateTime64(6, 'UTC'), String, Map(String, String)))
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(start_time)
ORDER BY (service_name, start_time, trace_id, span_id)
SETTINGS index_granularity = 8192;

-- 2. Continuous Profiling Call Stacks Table
CREATE TABLE IF NOT EXISTS default.profiles_samples (
    profile_id UUID DEFAULT generateUUIDv4(),
    service_name LowCardinality(String),
    profile_type LowCardinality(String),
    sample_timestamp DateTime64(6, 'UTC'),
    duration_nano UInt64,
    value Int64,
    unit LowCardinality(String),
    stack_trace String CODEC(ZSTD(3)),
    labels Map(String, String) CODEC(ZSTD(3))
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(sample_timestamp)
ORDER BY (service_name, profile_type, sample_timestamp)
SETTINGS index_granularity = 8192;
