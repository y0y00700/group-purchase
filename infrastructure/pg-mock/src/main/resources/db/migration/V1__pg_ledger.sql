CREATE TABLE pg_scenario (
  merchant_id text NOT NULL CHECK (merchant_id='local-merchant'),
  kind text NOT NULL CHECK (kind IN ('APPROVAL','REFUND')),
  business_id uuid NOT NULL,
  processing_delay_ms bigint NOT NULL DEFAULT 0 CHECK (processing_delay_ms BETWEEN 0 AND 30000),
  first_response_delay_ms bigint NOT NULL DEFAULT 0 CHECK (first_response_delay_ms BETWEEN 0 AND 10000),
  approval_decision text NOT NULL DEFAULT 'APPROVE' CHECK (approval_decision IN ('APPROVE','DECLINE')),
  transient_failures integer NOT NULL DEFAULT 0 CHECK (transient_failures BETWEEN 0 AND 3),
  operation_id uuid,
  PRIMARY KEY(merchant_id,kind,business_id),
  CHECK (kind='APPROVAL' OR approval_decision='APPROVE')
);
CREATE TABLE pg_operation (
  operation_id uuid PRIMARY KEY,
  merchant_id text NOT NULL CHECK (merchant_id='local-merchant'),
  kind text NOT NULL CHECK (kind IN ('APPROVAL','REFUND')),
  business_id uuid NOT NULL,
  payment_attempt_id uuid NOT NULL,
  approval_operation_id uuid,
  approval_kind text,
  amount bigint NOT NULL CHECK (amount>0),
  currency text NOT NULL CHECK (currency='KRW'),
  idempotency_key text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{16,128}$'),
  status text NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','DECLINED','RETRYABLE_FAILURE')),
  attempt integer NOT NULL CHECK (attempt>0),
  remaining_failures integer NOT NULL CHECK (remaining_failures BETWEEN 0 AND 3),
  processing_delay_ms bigint NOT NULL CHECK (processing_delay_ms BETWEEN 0 AND 30000),
  first_response_delay_ms bigint NOT NULL CHECK (first_response_delay_ms BETWEEN 0 AND 10000),
  approval_decision text NOT NULL CHECK (approval_decision IN ('APPROVE','DECLINE')),
  accepted_at timestamptz NOT NULL,
  ready_at timestamptz NOT NULL,
  completed_at timestamptz,
  failure_code text,
  CONSTRAINT pg_operation_key UNIQUE(merchant_id,kind,idempotency_key),
  CONSTRAINT pg_operation_business UNIQUE(merchant_id,kind,business_id),
  UNIQUE(merchant_id,kind,operation_id),
  UNIQUE(merchant_id,kind,business_id,operation_id),
  FOREIGN KEY(merchant_id,kind,business_id) REFERENCES pg_scenario(merchant_id,kind,business_id),
  FOREIGN KEY(merchant_id,approval_kind,payment_attempt_id,approval_operation_id)
    REFERENCES pg_operation(merchant_id,kind,business_id,operation_id),
  CHECK ((kind='APPROVAL' AND business_id=payment_attempt_id AND approval_operation_id IS NULL AND approval_kind IS NULL)
      OR (kind='REFUND' AND approval_operation_id IS NOT NULL AND approval_kind='APPROVAL' AND approval_decision='APPROVE')),
  CHECK ((status='SUCCEEDED')=(completed_at IS NOT NULL)),
  CHECK (status<>'DECLINED' OR kind='APPROVAL'),
  CHECK ((status IN ('DECLINED','RETRYABLE_FAILURE'))=(failure_code IS NOT NULL))
);
CREATE UNIQUE INDEX pg_one_full_refund ON pg_operation(approval_operation_id) WHERE kind='REFUND';
ALTER TABLE pg_scenario ADD FOREIGN KEY(merchant_id,kind,business_id,operation_id)
  REFERENCES pg_operation(merchant_id,kind,business_id,operation_id);
CREATE INDEX pg_pending ON pg_operation(ready_at,operation_id) WHERE status='PENDING';
CREATE TABLE pg_ledger (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE REFERENCES pg_operation(operation_id),
  amount bigint NOT NULL CHECK (amount>0),
  kind text NOT NULL CHECK (kind IN ('APPROVAL','REFUND')),
  occurred_at timestamptz NOT NULL
);
CREATE TABLE pg_execution_attempt (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  operation_id uuid NOT NULL REFERENCES pg_operation(operation_id),
  attempt integer NOT NULL CHECK (attempt>0),
  accepted_at timestamptz NOT NULL,
  ready_at timestamptz NOT NULL,
  result text NOT NULL CHECK (result IN ('PENDING','SUCCEEDED','DECLINED','RETRYABLE_FAILURE')),
  failure_code text,
  finished_at timestamptz,
  UNIQUE(operation_id,attempt),
  CHECK ((result='PENDING')=(finished_at IS NULL))
);
