-- flow 팩터(투자자별 순매수) 삭제: 공식 소스가 없고 쓰는 전략이 0개다. 가중치·창·분모 키를 지운다.
UPDATE strategies
SET config = config #- '{selection,factor_weights,flow}' #- '{selection,flow_window}' #- '{selection,flow_denom}'
WHERE config->'selection' ?| array['flow_window', 'flow_denom']
   OR config->'selection'->'factor_weights' ? 'flow';
