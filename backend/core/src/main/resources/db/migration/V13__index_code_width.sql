-- 업종지수 키 "KOSPI:이름" 은 20자를 넘을 수 있다.
ALTER TABLE index_ohlcv ALTER COLUMN index_code TYPE varchar(64);
ALTER TABLE index_ohlcv_coverage ALTER COLUMN index_code TYPE varchar(64);
