-- Do not guess which account submitted orders that predate this migration.
ALTER TABLE public.orders ADD COLUMN broker_account_key varchar(64);
CREATE INDEX ix_orders_user_account_symbol ON public.orders (user_id, broker_account_key, symbol);
