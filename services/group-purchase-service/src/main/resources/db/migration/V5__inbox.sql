CREATE TABLE app.inbox_processed_messages (
    consumer_role varchar(160) NOT NULL,
    message_id uuid NOT NULL,
    message_type varchar(100) NOT NULL,
    business_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    sequence bigint,
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
    topic_id varchar(64) NOT NULL,
    partition integer NOT NULL CHECK (partition>=0),
    offset_value bigint NOT NULL CHECK (offset_value>=0),
    processed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_role,message_id)
);
CREATE INDEX inbox_processed_business ON app.inbox_processed_messages(consumer_role,business_id,processed_at);

CREATE TABLE app.inbox_failures (
    failure_id uuid PRIMARY KEY,
    consumer_group varchar(200) NOT NULL,
    consumer_role varchar(160) NOT NULL,
    topic varchar(249) NOT NULL,
    topic_id varchar(64) NOT NULL,
    partition integer NOT NULL CHECK (partition>=0),
    offset_value bigint NOT NULL CHECK (offset_value>=0),
    message_id uuid,
    business_id uuid,
    raw_key bytea,
    raw_value bytea,
    raw_headers bytea NOT NULL,
    record_timestamp bigint NOT NULL,
    timestamp_type varchar(32) NOT NULL,
    leader_epoch integer,
    error_code varchar(120) NOT NULL,
    first_failed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    last_failed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    recovery_state varchar(16) NOT NULL DEFAULT 'UNRESOLVED' CHECK (recovery_state IN ('UNRESOLVED','RECOVERED')),
    dlt_state varchar(20) NOT NULL CHECK (dlt_state IN ('NOT_REQUIRED','PENDING','IN_FLIGHT','DLT_STORED','RETRY_WAIT','DLT_BLOCKED')),
    dlt_next_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    dlt_claim_id uuid,
    dlt_claim_until timestamptz(6),
    dlt_attempts integer NOT NULL DEFAULT 0 CHECK (dlt_attempts>=0),
    dlt_partition integer,
    dlt_offset bigint,
    dlt_error varchar(120),
    UNIQUE (consumer_group,topic_id,partition,offset_value),
    CHECK ((dlt_state='IN_FLIGHT')=(dlt_claim_id IS NOT NULL AND dlt_claim_until IS NOT NULL)),
    CHECK ((dlt_state='DLT_STORED')=(dlt_partition IS NOT NULL AND dlt_offset IS NOT NULL))
);
CREATE INDEX inbox_dlt_pending ON app.inbox_failures(dlt_next_at) WHERE dlt_state IN ('PENDING','RETRY_WAIT','IN_FLIGHT');

CREATE TABLE app.inbox_partition_states (
    consumer_group varchar(200) NOT NULL,
    topic varchar(249) NOT NULL,
    partition integer NOT NULL CHECK (partition>=0),
    consumer_role varchar(160) NOT NULL,
    topic_id varchar(64) NOT NULL,
    partition_count integer NOT NULL CHECK (partition_count>0),
    next_offset bigint NOT NULL CHECK (next_offset>=0),
    owner_token uuid NOT NULL,
    state_version bigint NOT NULL DEFAULT 1,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','RETRY_WAIT','HALTED','OFFSET_GAP')),
    failure_id uuid REFERENCES app.inbox_failures(failure_id),
    failure_offset bigint,
    retry_at timestamptz(6),
    reason varchar(120),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_group,topic,partition),
    CHECK (state <> 'ACTIVE' OR (failure_id IS NULL AND failure_offset IS NULL AND retry_at IS NULL)),
    CHECK (state NOT IN ('RETRY_WAIT','HALTED') OR (failure_id IS NOT NULL AND failure_offset>=next_offset)),
    CHECK ((state='RETRY_WAIT')=(retry_at IS NOT NULL))
);
CREATE INDEX inbox_partition_holds ON app.inbox_partition_states(consumer_group,state);

CREATE TABLE app.inbox_attempts (
    attempt_id uuid PRIMARY KEY,
    consumer_group varchar(200) NOT NULL,
    consumer_role varchar(160) NOT NULL,
    topic_id varchar(64) NOT NULL,
    partition integer NOT NULL,
    offset_value bigint NOT NULL,
    phase varchar(8) NOT NULL CHECK (phase IN ('HANDLE','DLT')),
    attempt_number integer NOT NULL CHECK (attempt_number>0),
    budget_charged boolean NOT NULL,
    started_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    ended_at timestamptz(6),
    outcome varchar(24),
    error_code varchar(120),
    CHECK ((ended_at IS NULL)=(outcome IS NULL))
);
CREATE INDEX inbox_attempt_source ON app.inbox_attempts(consumer_group,topic_id,partition,offset_value,phase,attempt_number);
