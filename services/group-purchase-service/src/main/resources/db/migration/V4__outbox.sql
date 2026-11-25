CREATE TABLE app.outbox_streams (
    direction text NOT NULL CHECK (direction = 'PAYMENT_COMMANDS'),
    payment_attempt_id uuid NOT NULL,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    PRIMARY KEY (direction, payment_attempt_id)
);

CREATE TABLE app.outbox_messages (
    message_id uuid PRIMARY KEY,
    message_type text NOT NULL,
    topic text NOT NULL,
    message_key text NOT NULL,
    group_purchase_id uuid NOT NULL,
    participation_id uuid,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    direction text,
    payment_attempt_id uuid,
    sequence bigint,
    envelope bytea NOT NULL CHECK (octet_length(envelope) BETWEEN 1 AND 65536),
    created_at timestamptz(6) NOT NULL,
    status text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','IN_FLIGHT','RETRY_WAIT','SUSPENDED','PUBLISHED')),
    next_attempt_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    claim_id uuid,
    claim_until timestamptz(6),
    claim_count bigint NOT NULL DEFAULT 0 CHECK (claim_count >= 0),
    retry_count bigint NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    first_failure_at timestamptz(6),
    last_failure_at timestamptz(6),
    last_error_code text,
    published_at timestamptz(6),
    kafka_partition integer,
    kafka_offset bigint,
    UNIQUE (direction, payment_attempt_id, sequence),
    CHECK ((message_type IN ('PaymentApprovalRequested','PaymentApprovalStopRequested','PaymentRefundRequested')
        AND topic = 'gp.payment.commands.v1' AND direction IS NOT NULL AND direction = 'PAYMENT_COMMANDS'
        AND payment_attempt_id IS NOT NULL AND participation_id IS NOT NULL AND sequence IS NOT NULL AND sequence > 0
        AND message_key = payment_attempt_id::text)
      OR (message_type = 'NotificationRequested' AND topic = 'gp.notification.requests.v1'
        AND direction IS NULL AND payment_attempt_id IS NULL AND sequence IS NULL AND message_key = group_purchase_id::text)),
    CHECK ((status = 'IN_FLIGHT' AND claim_id IS NOT NULL AND claim_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND claim_id IS NULL AND claim_until IS NULL)),
    CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL AND kafka_partition IS NOT NULL AND kafka_partition >= 0
        AND kafka_offset IS NOT NULL AND kafka_offset >= 0)
        OR (status <> 'PUBLISHED' AND published_at IS NULL AND kafka_partition IS NULL AND kafka_offset IS NULL))
);
CREATE INDEX outbox_candidates ON app.outbox_messages (next_attempt_at, created_at, message_id)
    WHERE status IN ('READY','RETRY_WAIT','IN_FLIGHT');
CREATE INDEX outbox_unfinished_stream ON app.outbox_messages (direction, payment_attempt_id, sequence)
    WHERE status <> 'PUBLISHED';

CREATE TABLE app.outbox_delivery_attempts (
    claim_id uuid PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES app.outbox_messages(message_id),
    claim_number bigint NOT NULL CHECK (claim_number > 0),
    started_at timestamptz(6) NOT NULL,
    claim_until timestamptz(6) NOT NULL,
    finished_at timestamptz(6),
    outcome text NOT NULL CHECK (outcome IN ('IN_FLIGHT','EXPIRED','PUBLISHED','RETRY_WAIT','SUSPENDED')),
    error_code text,
    kafka_partition integer,
    kafka_offset bigint,
    UNIQUE(message_id, claim_number),
    CHECK ((outcome = 'IN_FLIGHT' AND finished_at IS NULL) OR (outcome <> 'IN_FLIGHT' AND finished_at IS NOT NULL))
);
