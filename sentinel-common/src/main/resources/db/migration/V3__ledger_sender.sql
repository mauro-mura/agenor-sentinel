-- Which coordinator sent each request. The agent id is the same on every start ('coordinator'); the
-- node id is not, so the node tells one coordinator's life from the next. It is the node that
-- other nodes routed the coordinator's mail to when the request was sent: where the probe's reply
-- goes. A request whose sender died before the reply could arrive is lost with no error, since the
-- error would have been recorded by the dead process. Rows written before these columns existed
-- have no sender.
ALTER TABLE ledger ADD COLUMN sender_id TEXT;
ALTER TABLE ledger ADD COLUMN sender_node_id TEXT;
