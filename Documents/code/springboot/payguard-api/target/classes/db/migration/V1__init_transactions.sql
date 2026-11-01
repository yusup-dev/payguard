
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS transactions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    merchant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(128) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(128) NOT NULL DEFAULT 'IDR',
    customer_email VARCHAR(125) NOT NULL,
    va_number VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_transactions_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXPIRED')),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    webhook_url VARCHAR(1024),
    webhook_delivered BOOLEAN NOT NULL DEFAULT FALSE,
    webhook_attempts INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expired_at      TIMESTAMPTZ,
    CONSTRAINT pk_transactions PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_transactions_merchant_order
    ON transactions (merchant_id, order_id);

CREATE INDEX IF NOT EXISTS idx_transactions_merchant_id
    ON transactions (merchant_id);

CREATE INDEX IF NOT EXISTS idx_transactions_idempotency_key
    ON transactions (idempotency_key);


CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_transactions_updated_at ON transactions;
CREATE TRIGGER trg_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE transactions IS 'Core transaction ledger for the PAYGUARD Payment Gateway';
COMMENT ON COLUMN transactions.va_number IS 'Virtual Account number or Payment Reference Number returned to the merchant';
COMMENT ON COLUMN transactions.version IS 'JPA @Version field for Optimistic Locking — prevents lost-update anomalies';
COMMENT ON COLUMN transactions.idempotency_key IS 'X-Idempotency-Key header value, stored to detect and short-circuit duplicate requests';
