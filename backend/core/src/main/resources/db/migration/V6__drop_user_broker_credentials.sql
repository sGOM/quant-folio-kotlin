-- Single owner: broker credentials are read only from server secrets, never from users.
-- Drop the per-user credential columns and the V2 trigger that watched them (the trigger's
-- UPDATE OF column list depends on those columns, so it goes first).
-- kis_balance_reconcile_blocked stays: flags V2 already set keep blocking balance inference.
DROP TRIGGER block_kis_balance_after_account_change ON public.users;
DROP FUNCTION public.block_kis_balance_after_account_change();
ALTER TABLE public.users
    DROP COLUMN kis_app_key,
    DROP COLUMN kis_app_secret,
    DROP COLUMN kis_account_no,
    DROP COLUMN toss_app_key,
    DROP COLUMN toss_app_secret,
    DROP COLUMN toss_account_no;
