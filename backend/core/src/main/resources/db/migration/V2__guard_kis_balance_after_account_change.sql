-- Existing orders have no broker account provenance. Once credentials switch accounts,
-- a balance alone cannot identify fills in the new account or validate older history.
ALTER TABLE public.users ADD COLUMN kis_balance_reconcile_blocked boolean NOT NULL DEFAULT false;

CREATE FUNCTION public.block_kis_balance_after_account_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_broker text := coalesce(nullif(OLD.broker, ''), 'kis');
    new_broker text := coalesce(nullif(NEW.broker, ''), 'kis');
    -- Compare the whole normalized input. A prefix would hide a product-code change
    -- if the client trims whitespace that PostgreSQL btrim does not remove.
    old_account text := nullif(btrim(replace(OLD.kis_account_no, '-', '')), '');
    new_account text := nullif(btrim(replace(NEW.kis_account_no, '-', '')), '');
    old_registered boolean := coalesce(OLD.kis_app_key, '') <> '' AND coalesce(OLD.kis_app_secret, '') <> '';
    new_registered boolean := coalesce(NEW.kis_app_key, '') <> '' AND coalesce(NEW.kis_app_secret, '') <> '';
BEGIN
    IF OLD.kis_balance_reconcile_blocked OR old_broker <> new_broker THEN
        NEW.kis_balance_reconcile_blocked := true;
    ELSIF new_broker = 'kis' AND (old_account IS DISTINCT FROM new_account OR old_registered <> new_registered) THEN
        -- A live runner may already be using environment credentials before its first order.
        IF old_account IS NOT NULL
           OR EXISTS (SELECT 1 FROM public.orders WHERE user_id = OLD.id)
           OR EXISTS (SELECT 1 FROM public.strategies WHERE user_id = OLD.id) THEN
            NEW.kis_balance_reconcile_blocked := true;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER block_kis_balance_after_account_change
BEFORE UPDATE OF broker, kis_account_no, kis_app_key, kis_app_secret ON public.users
FOR EACH ROW EXECUTE FUNCTION public.block_kis_balance_after_account_change();
