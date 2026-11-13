CREATE TABLE app.group_purchases (
    id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'RECRUITING')),
    product_name text CHECK (char_length(product_name) BETWEEN 1 AND 100),
    product_description text CHECK (char_length(product_description) BETWEEN 1 AND 2000),
    unit_price bigint CHECK (unit_price BETWEEN 1 AND 1000000000),
    base_quantity integer CHECK (base_quantity BETWEEN 10 AND 1000000),
    final_deadline timestamptz(3),
    initial_recruitment_target integer,
    draft_version bigint NOT NULL CHECK (draft_version >= 1),
    created_at timestamptz(3) NOT NULL,
    updated_at timestamptz(3) NOT NULL CHECK (updated_at >= created_at),
    published_at timestamptz(3),
    CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND initial_recruitment_target IS NULL)
        OR (status = 'RECRUITING' AND published_at IS NOT NULL
            AND product_name IS NOT NULL AND product_description IS NOT NULL
            AND unit_price IS NOT NULL AND base_quantity IS NOT NULL AND final_deadline IS NOT NULL
            AND initial_recruitment_target IS NOT NULL
            AND initial_recruitment_target = (base_quantity * 6 + 4) / 5
            AND created_at <= published_at AND published_at <= updated_at
            AND final_deadline > published_at)
    )
);

CREATE INDEX group_purchases_public_order ON app.group_purchases (published_at DESC, id DESC)
    WHERE status = 'RECRUITING';
CREATE INDEX group_purchases_seller_order ON app.group_purchases (seller_id, created_at DESC, id DESC);

CREATE TABLE app.group_purchase_idempotency (
    member_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('REGISTER', 'REVISE', 'PUBLISH')),
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    fingerprint_version integer NOT NULL CHECK (fingerprint_version >= 1),
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    purchase_id uuid REFERENCES app.group_purchases (id),
    response_status integer,
    response_body text,
    response_location text,
    created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    PRIMARY KEY (member_id, operation, idempotency_key),
    CHECK (
        (purchase_id IS NULL AND response_status IS NULL AND response_body IS NULL AND response_location IS NULL)
        OR (purchase_id IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL
            AND ((operation = 'REGISTER' AND response_status = 201 AND response_location IS NOT NULL)
                OR (operation <> 'REGISTER' AND response_status = 200 AND response_location IS NULL)))
    )
);
