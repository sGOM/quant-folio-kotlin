-- Owner decision: delete the orders and positions that have no account provenance
-- (broker_account_key IS NULL; rows were backed up to CSV first). Executions go with their
-- orders (executions_order_id_fkey ON DELETE CASCADE). On an empty DB every statement is a no-op.
--
-- Those orders also blocked KIS paper balance inference for their user (Reconciler requires every
-- order of the user to carry the same key). Deleting them must not lift that block, so the user's
-- existing kis_balance_reconcile_blocked flag is set first — same effect, no longer row-dependent.
UPDATE public.users SET kis_balance_reconcile_blocked = true
WHERE id IN (SELECT user_id FROM public.orders WHERE broker_account_key IS NULL);
DELETE FROM public.positions WHERE broker_account_key IS NULL;
DELETE FROM public.orders WHERE broker_account_key IS NULL;
-- No code path writes a NULL key any more (orders get the client's key at reservation, positions
-- are created only by Fills.record with the order's key, and both columns are updatable = false).
DROP INDEX public.uq_positions_legacy_user_strategy_symbol;
