-- =====================================================================================
-- billing-core's tables in ONE tenant schema of the switch database, on PostgreSQL.
--
-- billing-core runs this itself the first time it serves a schema (PostgresTenantTables): each table that is
-- absent is created, in one step, with its indexes and — for a partitioned table — ALL its partitions.
-- Nobody applies this file by hand. It is the one source of the column definitions: the writer's column
-- list is checked against it by a test (PostgresTablesDdlTests).
--
--   * A tenant is a SCHEMA (btcl, res_44, res_44_7). Table names are unqualified: the connection's
--     search_path is the tenant's schema.
--   * Identifiers are never quoted, so PostgreSQL stores them in lower case (IdCall -> idcall).
--   * Times are "timestamp" WITHOUT time zone: the tenant's wall clock, written as the wire sent it.
--   * Every string column is "text": a long value must never be the reason a whole batch is refused.
--   * cdr, cdrerror, acc_chargeable are partitioned by RANGE on the row's time, one partition a MONTH,
--     plus a DEFAULT partition, so that no time can refuse a row. A month, not a day as the MySQL cdr has:
--     the idempotency lookup is by ChannelCallUuid, which has no time in it, so it probes the index of
--     EVERY partition on every batch — day partitions would add 365 probes a year to each batch.
--     The partition statements are generated (the months change); they are run between a table's
--     "@table" and "@indexes" sections:
--         CREATE TABLE cdr_p202610 PARTITION OF cdr
--             FOR VALUES FROM ('2026-10-01 00:00:00') TO ('2026-11-01 00:00:00');
--         CREATE TABLE cdr_pdefault PARTITION OF cdr DEFAULT;
--
-- The sections ("-- @table <name>", "-- @indexes <name>") are read by PostgresTenantTables. Keep them.
-- =====================================================================================

-- @table cdr
-- The first 104 columns are the legacy cdr's, in its insert order (cdr.ExtInsertColumns); the last six are
-- the ratified wire's own (ad-is-a-call §4): ResellerHierarchy, ChannelCallUuid, HangupCause, InPartnerUom,
-- IdPackageAccount, PackageAmount.
CREATE TABLE cdr (
    SwitchId                 integer        NOT NULL,
    IdCall                   bigint         NOT NULL,
    SequenceNumber           bigint         NOT NULL,
    FileName                 text,
    ServiceGroup             integer        NOT NULL,
    IncomingRoute            text,
    OriginatingIP            text,
    OPC                      integer,
    OriginatingCIC           integer,
    OriginatingCalledNumber  text,
    TerminatingCalledNumber  text,
    OriginatingCallingNumber text,
    TerminatingCallingNumber text,
    PrePaid                  integer,
    DurationSec              numeric(20,8),
    EndTime                  timestamp,
    ConnectTime              timestamp,
    AnswerTime               timestamp,
    ChargingStatus           integer,
    PDD                      real,
    CountryCode              text,
    AreaCodeOrLata           text,
    ReleaseDirection         integer,
    ReleaseCauseSystem       integer,
    ReleaseCauseEgress       integer,
    OutgoingRoute            text,
    TerminatingIP            text,
    DPC                      integer,
    TerminatingCIC           integer,
    StartTime                timestamp      NOT NULL,
    InPartnerId              integer,
    CustomerRate             numeric(20,8),
    OutPartnerId             integer,
    SupplierRate             numeric(20,8),
    MatchedPrefixY           text,
    UsdRateY                 numeric(20,8),
    MatchedPrefixCustomer    text,
    MatchedPrefixSupplier    text,
    InPartnerCost            numeric(20,8),
    OutPartnerCost           numeric(20,8),
    CostAnsIn                numeric(20,8),
    CostIcxIn                numeric(20,8),
    Tax1                     numeric(20,8),
    IgwRevenueIn             numeric(20,8),
    RevenueAnsOut            numeric(20,8),
    RevenueIgwOut            numeric(20,8),
    RevenueIcxOut            numeric(20,8),
    Tax2                     numeric(20,8),
    XAmount                  numeric(20,8),
    YAmount                  numeric(20,8),
    AnsPrefixOrig            text,
    AnsIdOrig                integer,
    AnsPrefixTerm            text,
    AnsIdTerm                integer,
    ValidFlag                integer,
    PartialFlag              integer,
    ReleaseCauseIngress      integer,
    InRoamingOpId            integer,
    OutRoamingOpId           integer,
    CalledPartyNOA           integer,
    CallingPartyNOA          integer,
    AdditionalSystemCodes    text,
    AdditionalPartyNumber    text,
    ResellerIds              text,
    ZAmount                  numeric(20,8),
    PreviousRoutes           text,
    E1Id                     integer,
    MediaIp1                 text,
    MediaIp2                 text,
    MediaIp3                 text,
    MediaIp4                 text,
    CallReleaseDuration      real,
    E1IdOut                  integer,
    InTrunkAdditionalInfo    text,
    OutTrunkAdditionalInfo   text,
    InMgwId                  text,
    OutMgwId                 text,
    MediationComplete        integer,
    Codec                    text,
    ConnectedNumberType      integer,
    RedirectingNumber        text,
    CallForwardOrRoamingType integer,
    OtherDate                timestamp,
    SummaryMetaTotal         numeric(20,8),
    TransactionMetaTotal     numeric(20,8),
    ChargeableMetaTotal      numeric(20,8),
    ErrorCode                text,
    NERSuccess               integer,
    RoundedDuration          numeric(20,8),
    PartialDuration          numeric(20,8),
    PartialAnswerTime        timestamp,
    PartialEndTime           timestamp,
    FinalRecord              bigint,
    Duration1                numeric(20,8),
    Duration2                numeric(20,8),
    Duration3                numeric(20,8),
    Duration4                numeric(20,8),
    PreviousPeriodCdr        integer,
    UniqueBillId             text,
    AdditionalMetaData       text,
    Category                 integer,
    SubCategory              integer,
    ChangedByJobId           bigint,
    SignalingStartTime       timestamp,
    ResellerHierarchy        text,
    ChannelCallUuid          text,
    HangupCause              text,
    InPartnerUom             text,
    IdPackageAccount         bigint,
    PackageAmount            numeric(20,8)
) PARTITION BY RANGE (StartTime);

-- @indexes cdr
-- ux_cdr_call is the hard backstop of the idempotency on (schema, ChannelCallUuid): a unique index of a
-- partitioned table must hold the partition key, and a redelivered record has the same StartTime.
CREATE UNIQUE INDEX ux_cdr_call ON cdr (ChannelCallUuid, StartTime);
CREATE INDEX ix_cdr_idcall ON cdr (IdCall);
CREATE INDEX ix_cdr_servicegroup_starttime ON cdr (ServiceGroup, StartTime);
CREATE INDEX ix_cdr_uniquebillid ON cdr (UniqueBillId);

-- @table cdrerror
-- The same columns as cdr (a rejected record keeps every field, and its ErrorCode says why).
CREATE TABLE cdrerror (LIKE cdr) PARTITION BY RANGE (StartTime);

-- @indexes cdrerror
CREATE UNIQUE INDEX ux_cdrerror_call ON cdrerror (ChannelCallUuid, StartTime);
CREATE INDEX ix_cdrerror_idcall ON cdrerror (IdCall);
CREATE INDEX ix_cdrerror_uniquebillid ON cdrerror (UniqueBillId);

-- @table acc_chargeable
-- The legacy acc_chargeable's 33 columns, in its insert order (acc_chargeable.ExtInsertColumns).
CREATE TABLE acc_chargeable (
    id                bigint        NOT NULL,
    uniqueBillId      text,
    idEvent           bigint        NOT NULL,
    transactionTime   timestamp     NOT NULL,
    assignedDirection smallint,
    description       text,
    glAccountId       bigint        NOT NULL,
    servicegroup      integer       NOT NULL,
    servicefamily     integer       NOT NULL,
    ProductId         bigint        NOT NULL,
    idBilledUom       text,
    idQuantityUom     text,
    BilledAmount      numeric(20,8),
    Quantity          numeric(20,8),
    unitPriceOrCharge numeric(20,8),
    Prefix            text,
    RateId            bigint        NOT NULL,
    TaxAmount1        numeric(20,8),
    TaxAmount2        numeric(20,8),
    TaxAmount3        numeric(20,8),
    VatAmount1        numeric(20,8),
    VatAmount2        numeric(20,8),
    VatAmount3        numeric(20,8),
    OtherAmount1      numeric(20,8),
    OtherAmount2      numeric(20,8),
    OtherAmount3      numeric(20,8),
    OtherDecAmount1   numeric(20,8),
    OtherDecAmount2   numeric(20,8),
    OtherDecAmount3   numeric(20,8),
    createdByJob      bigint,
    changedByJob      bigint,
    idBillingrule     integer       NOT NULL,
    jsonDetail        text,
    PRIMARY KEY (id, transactionTime)
) PARTITION BY RANGE (transactionTime);

-- @indexes acc_chargeable
CREATE INDEX ix_acc_chargeable_uniquebillid ON acc_chargeable (uniqueBillId);
CREATE INDEX ix_acc_chargeable_idevent ON acc_chargeable (idEvent);

-- @table summary_affected
-- The summary OUTBOX: one row per tenant batch, written in the batch's transaction. The summary service reads
-- "WHERE entity_type = ? AND id > <its offset> ORDER BY id" and deletes what it has consumed. Not partitioned.
-- data = base64( gzip( JSON array of {Cdr, Chargeables[]} ) ).
CREATE TABLE summary_affected (
    id          bigint       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    entity_type varchar(32)  NOT NULL,
    op          varchar(8)   NOT NULL DEFAULT 'add' CHECK (op IN ('add', 'subtract')),
    data        text         NOT NULL
);

-- @indexes summary_affected
CREATE INDEX ix_summary_affected_entity ON summary_affected (entity_type, id);
