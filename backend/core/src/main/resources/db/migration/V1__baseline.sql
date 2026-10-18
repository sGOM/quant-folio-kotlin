-- 원본 quant-folio Alembic head 0017 (커밋 8a563702) 재생 DB 의 public 스키마 덤프.
-- 기존 운영 DB 는 baselineOnMigrate 로 이 파일을 건너뛰고, 빈 DB(CI·테스트)만 이걸로 생성된다.

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE public.alerts (
    id integer NOT NULL,
    user_id integer,
    strategy_id integer NOT NULL,
    severity character varying(20) NOT NULL,
    code character varying(50),
    message text NOT NULL,
    is_read boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.alerts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.alerts_id_seq OWNED BY public.alerts.id;

CREATE TABLE public.backtests (
    id integer NOT NULL,
    strategy_id integer NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    total_return numeric(12,6),
    mdd numeric(12,6),
    sharpe numeric(12,6),
    result jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.backtests_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.backtests_id_seq OWNED BY public.backtests.id;

CREATE TABLE public.dart_financials (
    corp_code character varying(20) NOT NULL,
    bsns_year integer NOT NULL,
    reprt_code character varying(10) NOT NULL,
    fs_div character varying(10) NOT NULL,
    accounts jsonb NOT NULL,
    rcept_no character varying(30),
    rcept_dt date,
    confirmed_at date,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.executions (
    id integer NOT NULL,
    order_id integer NOT NULL,
    filled_qty numeric(18,4) NOT NULL,
    filled_price numeric(18,4) NOT NULL,
    fee numeric(18,4) DEFAULT '0'::numeric NOT NULL,
    executed_at timestamp with time zone DEFAULT now() NOT NULL,
    strategy_id integer
);

CREATE SEQUENCE public.executions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.executions_id_seq OWNED BY public.executions.id;

CREATE TABLE public.external_fetches (
    source character varying(40) NOT NULL,
    cache_key character varying(200) NOT NULL,
    row_count integer DEFAULT 0 NOT NULL,
    final boolean DEFAULT false NOT NULL,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.index_constituents (
    index_code character varying(40) NOT NULL,
    base_date date NOT NULL,
    symbol character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.index_ohlcv (
    index_code character varying(20) NOT NULL,
    trade_date date NOT NULL,
    index_name character varying(100),
    open numeric(18,4),
    high numeric(18,4),
    low numeric(18,4),
    close numeric(18,4),
    volume bigint,
    trading_value bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.index_ohlcv_coverage (
    index_code character varying(20) NOT NULL,
    covered_from date NOT NULL,
    covered_to date NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.kis_stock_master_snapshots (
    id integer NOT NULL,
    trade_date date NOT NULL,
    symbol character varying(20) NOT NULL,
    market character varying(10) NOT NULL,
    name character varying(100) NOT NULL,
    raw jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.kis_stock_master_snapshots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.kis_stock_master_snapshots_id_seq OWNED BY public.kis_stock_master_snapshots.id;

CREATE TABLE public.news_article_symbols (
    id integer NOT NULL,
    article_id integer NOT NULL,
    symbol character varying(20) NOT NULL
);

CREATE SEQUENCE public.news_article_symbols_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.news_article_symbols_id_seq OWNED BY public.news_article_symbols.id;

CREATE TABLE public.news_articles (
    id integer NOT NULL,
    source character varying(50) NOT NULL,
    title character varying(500) NOT NULL,
    url character varying(1000) NOT NULL,
    summary text,
    published_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.news_articles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.news_articles_id_seq OWNED BY public.news_articles.id;

CREATE TABLE public.orders (
    id integer NOT NULL,
    user_id integer NOT NULL,
    strategy_id integer,
    symbol character varying(20) NOT NULL,
    side character varying(8) NOT NULL,
    qty numeric(18,4) NOT NULL,
    price numeric(18,4),
    order_type character varying(20) DEFAULT 'limit'::character varying NOT NULL,
    kis_order_id character varying(64),
    status character varying(16) DEFAULT 'pending'::character varying NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    reason character varying(500),
    kis_order_org_no character varying(64)
);

CREATE SEQUENCE public.orders_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.orders_id_seq OWNED BY public.orders.id;

CREATE TABLE public.positions (
    id integer NOT NULL,
    user_id integer NOT NULL,
    symbol character varying(20) NOT NULL,
    qty numeric(18,4) DEFAULT '0'::numeric NOT NULL,
    avg_price numeric(18,4) DEFAULT '0'::numeric NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    strategy_id integer
);

CREATE SEQUENCE public.positions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.positions_id_seq OWNED BY public.positions.id;

CREATE TABLE public.price_ticks (
    "time" timestamp with time zone NOT NULL,
    symbol character varying(20) NOT NULL,
    open numeric(18,4) NOT NULL,
    high numeric(18,4) NOT NULL,
    low numeric(18,4) NOT NULL,
    close numeric(18,4) NOT NULL,
    volume bigint DEFAULT '0'::bigint NOT NULL
);

CREATE TABLE public.risk_limits (
    id integer NOT NULL,
    user_id integer NOT NULL,
    strategy_id integer,
    max_position_size numeric(18,4),
    daily_loss_limit numeric(18,4),
    stop_loss_pct numeric(6,4)
);

CREATE SEQUENCE public.risk_limits_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.risk_limits_id_seq OWNED BY public.risk_limits.id;

CREATE TABLE public.sector_map_snapshots (
    id integer NOT NULL,
    symbol character varying(20) NOT NULL,
    sector character varying(100) NOT NULL,
    snapshot_date date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.sector_map_snapshots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.sector_map_snapshots_id_seq OWNED BY public.sector_map_snapshots.id;

CREATE TABLE public.stock_daily_snapshots (
    trade_date date NOT NULL,
    symbol character varying(20) NOT NULL,
    market character varying(20),
    per numeric(18,4),
    pbr numeric(18,4),
    div numeric(18,4),
    market_cap bigint,
    shares bigint,
    open numeric(18,4),
    high numeric(18,4),
    low numeric(18,4),
    close numeric(18,4),
    volume bigint,
    trading_value bigint,
    change_pct numeric(12,4),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.stock_period_stats (
    start_date date NOT NULL,
    end_date date NOT NULL,
    investors character varying(100) NOT NULL,
    symbol character varying(20) NOT NULL,
    market character varying(20),
    change_pct numeric(12,4),
    open numeric(18,4),
    close numeric(18,4),
    volume bigint,
    trading_value bigint,
    net_buy_value numeric(24,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    name character varying(100)
);

CREATE TABLE public.strategies (
    id integer NOT NULL,
    user_id integer NOT NULL,
    name character varying(255) NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'draft'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    is_shared boolean DEFAULT false NOT NULL,
    shared_at timestamp with time zone,
    is_favorite boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    copied_from_id integer,
    description text,
    featured_backtest_id integer
);

CREATE SEQUENCE public.strategies_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.strategies_id_seq OWNED BY public.strategies.id;

CREATE TABLE public.strategy_likes (
    id integer NOT NULL,
    strategy_id integer NOT NULL,
    user_id integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.strategy_likes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.strategy_likes_id_seq OWNED BY public.strategy_likes.id;

CREATE TABLE public.users (
    id integer NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    kis_app_key character varying(512),
    kis_app_secret character varying(512),
    kis_account_no character varying(32),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    broker character varying(16) DEFAULT 'kis'::character varying NOT NULL,
    toss_app_key character varying(512),
    toss_app_secret character varying(512),
    toss_account_no character varying(32),
    display_name character varying(50)
);

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;

ALTER TABLE ONLY public.alerts ALTER COLUMN id SET DEFAULT nextval('public.alerts_id_seq'::regclass);

ALTER TABLE ONLY public.backtests ALTER COLUMN id SET DEFAULT nextval('public.backtests_id_seq'::regclass);

ALTER TABLE ONLY public.executions ALTER COLUMN id SET DEFAULT nextval('public.executions_id_seq'::regclass);

ALTER TABLE ONLY public.kis_stock_master_snapshots ALTER COLUMN id SET DEFAULT nextval('public.kis_stock_master_snapshots_id_seq'::regclass);

ALTER TABLE ONLY public.news_article_symbols ALTER COLUMN id SET DEFAULT nextval('public.news_article_symbols_id_seq'::regclass);

ALTER TABLE ONLY public.news_articles ALTER COLUMN id SET DEFAULT nextval('public.news_articles_id_seq'::regclass);

ALTER TABLE ONLY public.orders ALTER COLUMN id SET DEFAULT nextval('public.orders_id_seq'::regclass);

ALTER TABLE ONLY public.positions ALTER COLUMN id SET DEFAULT nextval('public.positions_id_seq'::regclass);

ALTER TABLE ONLY public.risk_limits ALTER COLUMN id SET DEFAULT nextval('public.risk_limits_id_seq'::regclass);

ALTER TABLE ONLY public.sector_map_snapshots ALTER COLUMN id SET DEFAULT nextval('public.sector_map_snapshots_id_seq'::regclass);

ALTER TABLE ONLY public.strategies ALTER COLUMN id SET DEFAULT nextval('public.strategies_id_seq'::regclass);

ALTER TABLE ONLY public.strategy_likes ALTER COLUMN id SET DEFAULT nextval('public.strategy_likes_id_seq'::regclass);

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.backtests
    ADD CONSTRAINT backtests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.dart_financials
    ADD CONSTRAINT dart_financials_pkey PRIMARY KEY (corp_code, bsns_year, reprt_code, fs_div);

ALTER TABLE ONLY public.executions
    ADD CONSTRAINT executions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.external_fetches
    ADD CONSTRAINT external_fetches_pkey PRIMARY KEY (source, cache_key);

ALTER TABLE ONLY public.index_constituents
    ADD CONSTRAINT index_constituents_pkey PRIMARY KEY (index_code, base_date, symbol);

ALTER TABLE ONLY public.index_ohlcv_coverage
    ADD CONSTRAINT index_ohlcv_coverage_pkey PRIMARY KEY (index_code, covered_from);

ALTER TABLE ONLY public.index_ohlcv
    ADD CONSTRAINT index_ohlcv_pkey PRIMARY KEY (index_code, trade_date);

ALTER TABLE ONLY public.kis_stock_master_snapshots
    ADD CONSTRAINT kis_stock_master_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.news_article_symbols
    ADD CONSTRAINT news_article_symbols_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.news_articles
    ADD CONSTRAINT news_articles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.price_ticks
    ADD CONSTRAINT price_ticks_pkey PRIMARY KEY ("time", symbol);

ALTER TABLE ONLY public.risk_limits
    ADD CONSTRAINT risk_limits_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sector_map_snapshots
    ADD CONSTRAINT sector_map_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.stock_daily_snapshots
    ADD CONSTRAINT stock_daily_snapshots_pkey PRIMARY KEY (trade_date, symbol);

ALTER TABLE ONLY public.stock_period_stats
    ADD CONSTRAINT stock_period_stats_pkey PRIMARY KEY (start_date, end_date, investors, symbol);

ALTER TABLE ONLY public.strategies
    ADD CONSTRAINT strategies_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.strategy_likes
    ADD CONSTRAINT strategy_likes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.kis_stock_master_snapshots
    ADD CONSTRAINT uq_kis_stock_master_symbol_date UNIQUE (symbol, trade_date);

ALTER TABLE ONLY public.news_article_symbols
    ADD CONSTRAINT uq_news_article_symbols UNIQUE (article_id, symbol);

ALTER TABLE ONLY public.news_articles
    ADD CONSTRAINT uq_news_articles_url UNIQUE (url);

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT uq_positions_user_strategy_symbol UNIQUE (user_id, strategy_id, symbol);

ALTER TABLE ONLY public.sector_map_snapshots
    ADD CONSTRAINT uq_sector_map_snapshot_symbol_date UNIQUE (symbol, snapshot_date);

ALTER TABLE ONLY public.strategy_likes
    ADD CONSTRAINT uq_strategy_likes_user UNIQUE (strategy_id, user_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

CREATE INDEX ix_alerts_user_id ON public.alerts USING btree (user_id);

CREATE INDEX ix_alerts_user_read_created ON public.alerts USING btree (user_id, is_read, created_at);

CREATE INDEX ix_backtests_strategy_id ON public.backtests USING btree (strategy_id);

CREATE INDEX ix_executions_order_id ON public.executions USING btree (order_id);

CREATE INDEX ix_executions_strategy_id ON public.executions USING btree (strategy_id);

CREATE INDEX ix_index_constituents_code_date ON public.index_constituents USING btree (index_code, base_date);

CREATE INDEX ix_kis_stock_master_date ON public.kis_stock_master_snapshots USING btree (trade_date);

CREATE INDEX ix_kis_stock_master_symbol ON public.kis_stock_master_snapshots USING btree (symbol);

CREATE INDEX ix_news_article_symbols_article_id ON public.news_article_symbols USING btree (article_id);

CREATE INDEX ix_news_article_symbols_symbol_article ON public.news_article_symbols USING btree (symbol, article_id);

CREATE INDEX ix_news_articles_published ON public.news_articles USING btree (published_at);

CREATE INDEX ix_orders_strategy_id ON public.orders USING btree (strategy_id);

CREATE INDEX ix_orders_symbol ON public.orders USING btree (symbol);

CREATE INDEX ix_orders_user_created ON public.orders USING btree (user_id, created_at);

CREATE INDEX ix_positions_strategy_id ON public.positions USING btree (strategy_id);

CREATE INDEX ix_positions_user_id ON public.positions USING btree (user_id);

CREATE INDEX ix_price_ticks_symbol_time ON public.price_ticks USING btree (symbol, "time");

CREATE INDEX ix_risk_limits_strategy_id ON public.risk_limits USING btree (strategy_id);

CREATE INDEX ix_risk_limits_user_id ON public.risk_limits USING btree (user_id);

CREATE INDEX ix_sector_map_snapshots_date ON public.sector_map_snapshots USING btree (snapshot_date);

CREATE INDEX ix_sector_map_snapshots_symbol_date ON public.sector_map_snapshots USING btree (symbol, snapshot_date);

CREATE INDEX ix_stock_daily_snapshots_symbol_date ON public.stock_daily_snapshots USING btree (symbol, trade_date);

CREATE INDEX ix_stock_period_stats_range ON public.stock_period_stats USING btree (start_date, end_date);

CREATE INDEX ix_stock_period_stats_symbol ON public.stock_period_stats USING btree (symbol);

CREATE INDEX ix_strategies_user_id ON public.strategies USING btree (user_id);

CREATE INDEX ix_strategy_likes_strategy ON public.strategy_likes USING btree (strategy_id);

CREATE UNIQUE INDEX ix_users_email ON public.users USING btree (email);

CREATE INDEX price_ticks_time_idx ON public.price_ticks USING btree ("time" DESC);

CREATE INDEX stock_daily_snapshots_trade_date_idx ON public.stock_daily_snapshots USING btree (trade_date DESC);

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.backtests
    ADD CONSTRAINT backtests_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.executions
    ADD CONSTRAINT executions_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.executions
    ADD CONSTRAINT executions_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.news_article_symbols
    ADD CONSTRAINT news_article_symbols_article_id_fkey FOREIGN KEY (article_id) REFERENCES public.news_articles(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.risk_limits
    ADD CONSTRAINT risk_limits_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.risk_limits
    ADD CONSTRAINT risk_limits_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.strategies
    ADD CONSTRAINT strategies_copied_from_id_fkey FOREIGN KEY (copied_from_id) REFERENCES public.strategies(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.strategies
    ADD CONSTRAINT strategies_featured_backtest_id_fkey FOREIGN KEY (featured_backtest_id) REFERENCES public.backtests(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.strategies
    ADD CONSTRAINT strategies_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.strategy_likes
    ADD CONSTRAINT strategy_likes_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.strategies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.strategy_likes
    ADD CONSTRAINT strategy_likes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- hypertable 변환 (원본 0001·0013 마이그레이션과 동일)
SELECT create_hypertable('price_ticks', 'time', if_not_exists => TRUE);
SELECT create_hypertable('stock_daily_snapshots', 'trade_date', if_not_exists => TRUE);
