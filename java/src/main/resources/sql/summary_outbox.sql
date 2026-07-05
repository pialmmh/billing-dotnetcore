-- =====================================================================================
-- summary outbox + bookmark — billing-core CREATES + WRITES the outbox; the summary-service
-- READS it, advances the per-bean offset, and reaps consumed rows.
--
-- Apply this to EACH tenant/reseller schema that billing writes cdrs into (e.g. res_NNN,
-- and the admin schema) BEFORE enabling outbox mode (Billing:Summary:Enabled=true) — the
-- outbox INSERT runs in the cdr batch transaction, so a missing table rolls the batch back.
--
-- Decoupling contract (see /tmp/shared-instruction/summary-service-outbox-design.md):
--   * billing writes ONE summary_affected row per cdr batch, atomically with cdr/chargeable.
--   * data = the batch's rated cdrs (each + ALL its acc_chargeable legs), JSON → gzip → base64.
--   * op   = 'add' for normal batches; corrections write 'subtract' (old values) + 'add' (new values).
--   * Kafka carries only a PING; this table is the durable hand-off.
--   * billing serializes batches per schema via GET_LOCK('billing_batch_<schema>') held across the
--     commit, so outbox ids are COMMIT-ordered and the consumer's id>offset cursor never skips rows.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS summary_affected (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32)  NOT NULL,                  -- 'cdr' (future: other event entities)
    op          ENUM('add','subtract') NOT NULL DEFAULT 'add',  -- how the consumer folds the row in:
                                                        -- normal batch = 'add'; a correction writes a
                                                        -- 'subtract' row (the OLD values) + an 'add' row
                                                        -- (the NEW values) in ONE billing transaction
    data        LONGTEXT     NOT NULL,                  -- gzip(JSON array of {Cdr, Chargeables[]}) then base64
    PRIMARY KEY (id),
    KEY ix_entity (entity_type, id)                     -- the summary-service scans WHERE entity_type=? AND id>offset
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- NOTE: the per-bean bookmark table `summary_offset` is OWNED + created + managed by the SUMMARY-SERVICE,
-- not billing — billing only WRITES `summary_affected`. The summary-service ensures its offset row per bean
-- and advances it in the SAME transaction as that bean's summary write. (Tip for that side: name the column
-- `last_offset` — OFFSET is a MySQL reserved word.) It is intentionally NOT created here.
