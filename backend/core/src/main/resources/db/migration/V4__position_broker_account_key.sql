-- A pre-existing position can contain fills from multiple accounts. Keep it unassigned.
ALTER TABLE public.positions ADD COLUMN broker_account_key varchar(64);
-- Old fills did not update an account-scoped position. Exclude them from its cash replay.
ALTER TABLE public.executions ADD COLUMN account_position_qty numeric(18,4);
ALTER TABLE public.positions DROP CONSTRAINT uq_positions_user_strategy_symbol;
ALTER TABLE public.positions ADD CONSTRAINT uq_positions_user_account_strategy_symbol
    UNIQUE (user_id, broker_account_key, strategy_id, symbol);

-- Keep the old uniqueness rule for rows without account provenance. Both constraints
-- allow NULL strategy_id so deleting a strategy can preserve detached positions.
CREATE UNIQUE INDEX uq_positions_legacy_user_strategy_symbol
    ON public.positions (user_id, strategy_id, symbol) WHERE broker_account_key IS NULL;
