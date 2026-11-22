-- Single owner: strategy sharing, likes, copy provenance, featured backtest and the
-- sharing nickname were removed (owner decision; rows were backed up to CSV first).
-- DROP TABLE takes the table's indexes, constraints and owned id sequence with it;
-- DROP COLUMN takes the FKs on copied_from_id / featured_backtest_id with it.
-- users rows are kept.
DROP TABLE public.strategy_likes;
ALTER TABLE public.strategies
    DROP COLUMN is_shared,
    DROP COLUMN shared_at,
    DROP COLUMN copied_from_id,
    DROP COLUMN featured_backtest_id;
ALTER TABLE public.users DROP COLUMN display_name;
