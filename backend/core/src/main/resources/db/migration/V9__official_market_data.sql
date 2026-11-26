ALTER TABLE stock_daily_snapshots
    ADD COLUMN prev_diff numeric(18,4),
    ADD COLUMN stock_kind varchar(20);

CREATE TABLE dividends (
    symbol varchar(20) NOT NULL,
    record_date date NOT NULL,
    dps numeric(18,4) NOT NULL,
    announced_on date,
    source varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, record_date)
);
