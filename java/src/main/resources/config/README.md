# Tenant configuration — telcobright-billing-core

Routesphere-style, split into two parts:

**1. Tenant registry** — lives in `src/main/resources/application.properties` (NOT in this folder).
It only says WHICH tenants this instance loads and the ACTIVE profile per tenant:

```properties
billing.tenants[0].name=ccl78
billing.tenants[0].enabled=true
billing.tenants[0].profile=dev
```

**2. Per-tenant / per-profile detail** — this folder:

```
config/
└── tenants/
    └── <tenant>/                       # one folder per tenant (e.g. ccl78)
        └── <profile>/                  # dev | staging | prod
            └── profile-<profile>.yml   # the profile root
```

Read from the **classpath** by `ProfileConfigReader`. An external on-disk dir can override the bundled
tree without a rebuild via `billing.config.dir` (the deploy rsync model) — the override is used only
when the file actually exists there.

## What we kept vs. routesphere

| routesphere | here | why |
|---|---|---|
| `application.properties` tenant list + active profile | **same** (`billing.tenants[i].*`) | routesphere convention — properties enable/disable + pick profile |
| `tenants/<t>/<p>/profile-<p>.yml` | **same** | faithful per-tenant/per-profile root |
| hierarchy via config-manager `/get-specific-tenant-root` | **same** | parent/dbName/children are **not** in YAML, by design |
| `channels/{esl,sigtran,http,omniqueue,memledger}/*.yml` | **omitted** | this service runs no live-call channels — it is config-manager-fed and only rates |

So a billing profile carries exactly three concerns: **config-manager** (where to fetch each tenant's
`DynamicContext`), **config-events** (the Kafka reload trigger), and **mediation** (which rating modules
are on). No real secrets are committed here.

## How it is consumed

On start, `ProfileConfigReader.ReadSelection()` reads the registry from `application.properties`; then for
the active (first enabled) tenant it reads `config/tenants/<name>/<profile>/profile-<profile>.yml` from the
classpath. `TenantConfigSync`/`BillingBootstrap` fetch each tenant root from config-manager over HTTP and
build the in-memory `Tenant` tree where `tenant.Context` is a `DynamicContext` holding a `MediationContext`.
A Kafka `config_event_loader_<tenant>` message triggers a debounced (3000 ms) re-fetch. See `TenantConfigSync/`.

## Add a tenant / profile
- **New tenant:** append `billing.tenants[N].{name,enabled,profile}` in `application.properties` and drop a
  `config/tenants/<name>/<profile>/profile-<profile>.yml` here.
- **New profile for a tenant:** add a `config/tenants/<name>/<profile>/` folder with its `profile-<profile>.yml`,
  and point that tenant's `billing.tenants[i].profile` at it.
