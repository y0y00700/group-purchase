ALTER TABLE app.group_purchases ADD COLUMN recruited_quantity integer NOT NULL DEFAULT 0;
ALTER TABLE app.group_purchases ADD CONSTRAINT group_purchases_recruited_quantity_check
    CHECK (recruited_quantity BETWEEN 0 AND COALESCE(initial_recruitment_target, 0));

CREATE TABLE app.participations (
    id uuid PRIMARY KEY,
    group_purchase_id uuid NOT NULL REFERENCES app.group_purchases (id),
    buyer_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    unit_price bigint NOT NULL CHECK (unit_price BETWEEN 1 AND 1000000000),
    status text NOT NULL CHECK (status IN ('APPLIED', 'CANCELLED')),
    applied_at timestamptz(3) NOT NULL,
    cancelled_at timestamptz(3),
    CHECK ((status = 'APPLIED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_at >= applied_at))
);

CREATE INDEX participations_applied_buyer ON app.participations (group_purchase_id, buyer_id)
    WHERE status = 'APPLIED';
CREATE INDEX participations_buyer_order ON app.participations (buyer_id, applied_at DESC, id DESC);

CREATE TABLE app.participation_idempotency (
    buyer_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('APPLY', 'CANCEL')),
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    fingerprint_version integer NOT NULL CHECK (fingerprint_version >= 1),
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    participation_id uuid REFERENCES app.participations (id),
    response_status integer,
    response_body text,
    response_location text,
    created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    PRIMARY KEY (buyer_id, operation, idempotency_key),
    CHECK (
        (participation_id IS NULL AND response_status IS NULL AND response_body IS NULL AND response_location IS NULL)
        OR (participation_id IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL
            AND ((operation = 'APPLY' AND response_status = 201 AND response_location IS NOT NULL)
                OR (operation = 'CANCEL' AND response_status = 200 AND response_location IS NULL)))
    )
);
