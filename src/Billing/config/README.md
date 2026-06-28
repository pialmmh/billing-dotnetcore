# Tenant configuration — telcobright-billing-core

This mirrors **routesphere's** multi-tenant config layout so the two sides stay
recognisable to anyone who knows one of them.

## Layout

```
config/
├── tenants.yml                         # which tenants load + active profile (≈ routesphere application.properties)
└── tenants/
    └── <tenant>/                       # one folder per tenant (e.g. ccl)
        └── <profile>/                  # dev | staging | prod | mock
            └── profile-<profile>.yml   # the profile root
```

`tenants.yml` selects the active set; each `profile-<profile>.yml` carries that
tenant+profile's settings.

## What we kept vs. routesphere

| routesphere | here | why |
|---|---|---|
| `tenants/<t>/<p>/profile-<p>.yml` | same | faithful per-tenant/per-profile root |
| `application.properties` tenant list | `tenants.yml` | same idea, YAML form |
| hierarchy via config-manager `/get-specific-tenant-root` | same | parent/dbName/children are **not** in YAML, by design |
| `channels/{esl,sigtran,http,omniqueue,memledger}/*.yml` | **omitted** | this service runs no live-call channels — it is config-manager-fed and only rates |
| inline `quarkus.datasource` password, Kafka SASL, etc. | **omitted** | code-master secrets rule — secrets come from OpenBao, never YAML |

So a billing profile carries exactly three concerns: **config-manager** (where to
fetch each tenant's `DynamicContext`), **config-events** (the Kafka reload trigger),
and **mediation** (which rating modules are on).

## How it is consumed

On start, `TenantConfigSync` reads `tenants.yml`, then for each enabled tenant reads
its `profile-<profile>.yml`, fetches the tenant root from config-manager over HTTP, and
builds the in-memory `Tenant` tree where `tenant.Context` is a `DynamicContext` that
holds a `MediationContext`. A Kafka `config_event_loader_<tenant>` message triggers a
debounced (3000 ms) re-fetch. See `TenantConfigSync/`.
