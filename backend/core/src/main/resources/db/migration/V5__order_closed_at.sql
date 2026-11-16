-- Set by the reconciler when an order can receive no more fills and its recorded executions are final.
-- Closed orders leave reconciliation and no longer hold new orders. Existing rows stay open (NULL).
ALTER TABLE public.orders ADD COLUMN closed_at timestamptz;
