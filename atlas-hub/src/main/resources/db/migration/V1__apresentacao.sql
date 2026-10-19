-- Apresentacao persistida (design §3.1). Fatos (nos, arestas, estado) vivem em memoria e vem das fontes;
-- aqui fica so o que o operador fez a mao e o que precisa sobreviver a um restart.

-- Posicao manual de um no; sem linha, vale o layout automatico. rev e a versao do If-Match (§3.2).
create table layout_node (
    node_key   text primary key,
    x          double precision not null,
    y          double precision not null,
    pinned     boolean not null default false,
    rev        bigint not null,
    updated_by text not null,
    updated_at timestamptz not null
);

-- Quem fez o que: abertura de log, login break-glass (ok e falha), enrollment, revogacao.
create table audit (
    id     bigserial primary key,
    at     timestamptz not null,
    actor  text not null,
    action text not null,
    target text
);
create index audit_at on audit (at desc);

-- Historico minimo de estado (§3.3): retencao de 7 dias, alimenta o "mudou ha X".
create table status_change (
    id         bigserial primary key,
    node_key   text not null,
    from_state text,
    to_state   text not null,
    reason     text,
    at         timestamptz not null
);
create index status_change_node_at on status_change (node_key, at desc);
create index status_change_at on status_change (at);
