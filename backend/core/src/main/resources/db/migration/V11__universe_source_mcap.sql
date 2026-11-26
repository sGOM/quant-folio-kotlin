-- 지수 구성종목 소스(KOSPI200·KOSPI100·KRX300) 폐지: KOSPI_MCAP(시총 상위, 시점별) + pool_size 로 옮긴다. pick·나머지 설정은 그대로.
-- 매핑: KOSPI200 -> 200, KOSPI100 -> 100, KRX300 -> 300 (시총 상위 N 풀, 그 위에 모멘텀 pick 좁히기).
UPDATE strategies
SET config = jsonb_set(
        jsonb_set(config, '{selection,universe_rule,source}', '"KOSPI_MCAP"'),
        '{selection,universe_rule,pool_size}',
        to_jsonb(CASE config->'selection'->'universe_rule'->>'source'
                     WHEN 'KOSPI200' THEN 200 WHEN 'KOSPI100' THEN 100 ELSE 300 END))
WHERE config->'selection'->'universe_rule'->>'source' IN ('KOSPI200', 'KOSPI100', 'KRX300');
