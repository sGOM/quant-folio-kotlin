-- flow 팩터(투자자별 순매수) 삭제: 공식 소스가 없고 쓰는 전략이 0개다. 가중치·창·분모 키를 지운다.
-- flow 가중치 > 0 인 전략이 있으면 조용히 지우지 않고 실패한다 — 지우면 가중치 합이 1 에서 어긋난다. 사람이 먼저 가중치를 옮긴다.
DO $$
DECLARE ids text;
BEGIN
    SELECT string_agg(id::text, ',' ORDER BY id) INTO ids FROM strategies
    WHERE jsonb_typeof(config->'selection'->'factor_weights'->'flow') = 'number'
      AND (config->'selection'->'factor_weights'->>'flow')::numeric > 0;
    IF ids IS NOT NULL THEN
        RAISE EXCEPTION 'V12: flow 가중치 > 0 인 전략이 있다(id %) — 가중치를 다른 팩터로 옮긴 뒤 다시 배포', ids;
    END IF;
END $$;
UPDATE strategies
SET config = config #- '{selection,factor_weights,flow}' #- '{selection,flow_window}' #- '{selection,flow_denom}'
WHERE config->'selection' ?| array['flow_window', 'flow_denom']
   OR config->'selection'->'factor_weights' ? 'flow';
