-- The agent id of the coordinator that sent each request. The id changes on every start, so it
-- tells one coordinator's life from the next: a request whose sender died before the reply could
-- arrive is lost with no error, since the error would have been recorded by the dead process.
-- Rows written before this column existed have no sender.
ALTER TABLE ledger ADD COLUMN sender_id TEXT;
