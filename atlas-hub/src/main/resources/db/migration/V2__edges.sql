-- Edges e tokens de enrollment (antes em memoria no EnrollmentService). Precisam sobreviver ao restart do hub:
-- sem isso o EdgeUpgradeCheck nao acha o edge do CN do certificado e recusa (403) para sempre, e o edge ignora o
-- token porque ja tem credencial no volume dele.

-- Um edge por maquina; id = 'edge-' || machine (o CN do certificado). So o certificado vigente (fingerprint
-- SHA-256 do DER) de um edge 'active' entra no handshake. pending = criado/re-enroll sem cert ainda.
create table edge (
    id               text primary key,
    machine          text not null unique check (machine ~ '^[a-z0-9][a-z0-9-]{0,30}$'),
    status           text not null check (status in ('pending', 'active', 'revoked')),
    cert_fingerprint text check (cert_fingerprint ~ '^[0-9a-f]{64}$'),
    cert_serial      text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    revoked_at       timestamptz,
    check (id = 'edge-' || machine),
    check (status <> 'active' or cert_fingerprint is not null)
);

-- Token de uso unico: so o SHA-256 (hex) fica no banco, nunca o token. Consumo atomico por
-- update ... where used = false and expires_at > agora returning. Vencidos sao apagados pelo hub (de hora em hora
-- e a cada emissao).
create table enrollment_token (
    token_sha256 text primary key check (token_sha256 ~ '^[0-9a-f]{64}$'),
    edge_id      text not null references edge (id) on delete cascade,
    expires_at   timestamptz not null,
    used         boolean not null default false,
    created_at   timestamptz not null
);
create index enrollment_token_edge on enrollment_token (edge_id);
create index enrollment_token_expires on enrollment_token (expires_at);
