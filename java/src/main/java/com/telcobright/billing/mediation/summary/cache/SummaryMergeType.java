// Split from legacy Mediation/Summary/Cache/ICacheble.cs.
package com.telcobright.billing.mediation.summary.cache;

/** Add (new CDRs) or Substract (erased/reprocessed CDRs) when merging a summary (legacy spelling kept). */
public enum SummaryMergeType { Add, Substract }
