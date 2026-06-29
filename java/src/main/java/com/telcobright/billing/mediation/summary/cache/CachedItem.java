// Split from legacy Mediation/Summary/Cache/ICacheble.cs.
package com.telcobright.billing.mediation.summary.cache;

/** What the cache returns for a keyed item (legacy CachedItem). */
public final class CachedItem<TKey, TEntity> {
    public final TKey Key;
    public final TEntity Entity;

    public CachedItem(TKey key, TEntity entity) {
        Key = key;
        Entity = entity;
    }
}
