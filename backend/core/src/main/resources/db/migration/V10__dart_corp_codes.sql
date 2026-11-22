CREATE TABLE dart_corp_codes (
    symbol varchar(6) PRIMARY KEY,
    corp_code varchar(8) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
