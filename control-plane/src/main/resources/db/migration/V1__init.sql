-- AGENTPLANE control plane schema.
-- Postgres holds anything that needs transactions and joins: tenants, run records, the audit
-- log. High-volume schemaless run output (logs / tool-call traces) lives in MongoDB instead -
-- see trace.RunTrace. This migration is the single source of truth for the relational schema;
-- Hibernate runs with ddl-auto=validate so the JPA entities must never drift from it.

create table tenants (
    id                    uuid primary key,
    name                  varchar(255) not null,
    slug                  varchar(100) not null,
    namespace             varchar(100) not null,
    quota_cpu             varchar(32)  not null,
    quota_memory          varchar(32)  not null,
    max_concurrent_runs   integer      not null,
    provisioning_status   varchar(32)  not null,
    created_at            timestamptz  not null,
    constraint uq_tenants_slug unique (slug),
    constraint uq_tenants_namespace unique (namespace)
);

create table agent_runs (
    id                 uuid primary key,
    tenant_id          uuid not null references tenants (id),
    agent_name         varchar(255) not null,
    image              varchar(512) not null,
    prompt             text not null,
    model              varchar(255) not null,
    max_steps          integer not null,
    timeout_seconds    integer not null,
    env                jsonb not null default '{}'::jsonb,
    resource_cpu       varchar(32) not null,
    resource_memory    varchar(32) not null,
    status             varchar(32) not null,
    created_at         timestamptz not null,
    started_at         timestamptz,
    finished_at        timestamptz,
    k8s_job_name       varchar(255),
    namespace          varchar(100),
    attempt            integer not null default 1,
    idempotency_key    varchar(255) not null,
    pod_phase          varchar(64),
    exit_code          integer,
    restart_count      integer not null default 0,
    node_name          varchar(255),
    message            text,
    step_count         integer not null default 0,
    token_prompt       integer,
    token_completion   integer,
    token_total        integer,
    cost_usd           numeric(12, 6),
    version            bigint not null default 0,
    constraint uq_agent_runs_idempotency_key unique (idempotency_key)
);

create index ix_agent_runs_tenant_id on agent_runs (tenant_id);
create index ix_agent_runs_status on agent_runs (status);
create index ix_agent_runs_created_at on agent_runs (created_at desc);
create index ix_agent_runs_tenant_status on agent_runs (tenant_id, status);

create table audit_events (
    id          uuid primary key,
    tenant_id   uuid,
    run_id      uuid,
    event_type  varchar(100) not null,
    detail      text not null,
    created_at  timestamptz not null
);

create index ix_audit_events_run_id on audit_events (run_id);
create index ix_audit_events_tenant_id on audit_events (tenant_id);
create index ix_audit_events_created_at on audit_events (created_at desc);
