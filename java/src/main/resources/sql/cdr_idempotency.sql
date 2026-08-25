-- Idempotency backstop for at-least-once CDR ingest (T3): a redelivered UniqueBillId cannot double-bill.
--
-- MySqlCdrBatchRunner.FilterAlreadyBilled dedups on cdr.UniqueBillId under the per-schema batch lock
-- (GET_LOCK('billing_batch_<schema>')), so a redelivered poll-batch re-writes nothing already committed.
-- This UNIQUE index is the HARD backstop if two processes ever race past that dedup: the second INSERT of a
-- duplicate UniqueBillId fails, the batch rolls back (all-or-nothing), and the dedup catches it on retry.
--
-- UniqueBillId is nullable, and MySQL permits multiple NULLs in a UNIQUE index, so cdrs with no producer key
-- are unaffected (they also cannot be deduped — the producer must supply a key for at-least-once safety).
--
-- Apply PER TENANT SCHEMA (telcobright, res_NNN, ...).
--
-- PROD CAVEAT (.110): before creating this on a large live cdr table, confirm there are no duplicate NON-NULL
-- (and no duplicate empty-string) UniqueBillId rows — the ALTER fails otherwise:
--     SELECT UniqueBillId, COUNT(*) c FROM cdr
--       WHERE UniqueBillId IS NOT NULL AND UniqueBillId <> '' GROUP BY UniqueBillId HAVING c > 1;
-- and expect a long online DDL on a multi-million-row table (use pt-online-schema-change / gh-ost if needed).

-- UNPARTITIONED cdr table (e.g. reseller schemas without partitioning):
ALTER TABLE cdr ADD UNIQUE INDEX ux_cdr_uniquebillid (UniqueBillId);

-- PARTITIONED cdr table (partitioned by starttime): MySQL requires a UNIQUE index to include every column of
-- the partitioning function, so the key is (UniqueBillId, StartTime). A redelivered CDR is byte-identical
-- (same StartTime), so this still blocks redelivery, and UniqueBillId stays the leading column for the dedup
-- SELECT. Use this variant wherever `SHOW CREATE TABLE cdr` shows a PARTITION BY clause:
-- ALTER TABLE cdr ADD UNIQUE INDEX ux_cdr_uniquebillid (UniqueBillId, StartTime);
