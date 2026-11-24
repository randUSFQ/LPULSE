-- Esquema inicial. Las reglas de integridad viven tambien en la BD (defensa en profundidad).

create table batch (
    id          bigserial primary key,
    sku         varchar(64) not null,
    location    varchar(32) not null,
    lot_code    varchar(64) not null,
    expires_on  date        not null,
    physical    int         not null check (physical >= 0),
    reserved    int         not null default 0 check (reserved >= 0),
    constraint ck_reserved_le_physical check (reserved <= physical),   -- nunca saldo negativo
    constraint uq_batch unique (sku, location, lot_code)
);
create index ix_batch_fefo on batch (sku, location, expires_on, id);

create table reservation (
    id          uuid primary key,
    sku         varchar(64) not null,
    location    varchar(32) not null,
    quantity    int         not null check (quantity > 0),
    destination varchar(64) not null,
    status      varchar(16) not null,
    created_at  timestamptz not null
);

create table reservation_line (
    reservation_id uuid   not null references reservation (id),
    batch_id       bigint not null references batch (id),
    lot_code       varchar(64) not null,
    quantity       int    not null check (quantity > 0),
    primary key (reservation_id, batch_id)
);

-- Idempotencia de comandos: la clave es "<client>:<Idempotency-Key>"
create table idempotency_key (
    key          varchar(200) primary key,
    request_hash char(64)     not null,
    response     jsonb,
    created_at   timestamptz  not null
);

-- Outbox: el evento se escribe en la MISMA transaccion que el cambio de dominio
create table outbox (
    id             uuid primary key,
    event_type     varchar(64)  not null,
    routing_key    varchar(128) not null,
    payload        jsonb        not null,   -- envelope completo
    correlation_id varchar(64),
    occurred_at    timestamptz  not null,
    published_at   timestamptz
);
create index ix_outbox_pending on outbox (occurred_at) where published_at is null;

-- Consumidores idempotentes: (consumidor, eventId)
create table processed_event (
    consumer     varchar(64) not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);

create table reorder_config (
    sku           varchar(64) primary key,
    reorder_point int         not null check (reorder_point >= 0),
    target_level  int         not null check (target_level > reorder_point),
    supplier      varchar(64) not null
);

create table replenishment_suggestion (
    id            uuid primary key,
    sku           varchar(64) not null,
    supplier      varchar(64) not null,
    suggested_qty int         not null check (suggested_qty > 0),
    status        varchar(16) not null default 'OPEN',
    created_at    timestamptz not null default now(),
    approved_by   varchar(64),
    approved_at   timestamptz
);
-- A lo sumo una alerta ABIERTA por SKU: la repeticion de eventos no duplica alertas
create unique index ux_suggestion_open on replenishment_suggestion (sku) where status = 'OPEN';

-- Vista materializada por el consumidor del dashboard
create table dashboard_view (
    sku                 varchar(64) primary key,
    reservations_count  int         not null default 0,
    last_reservation_id uuid,
    last_status         varchar(16),
    last_event_at       timestamptz not null,
    last_updated_at     timestamptz not null
);
