/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.transaction;

import java.util.Iterator;
import java.util.Map.Entry;

import org.lealone.storage.StorageMap;
import org.lealone.type.DataType;
import org.lealone.util.DataUtils;

/**
 * A map that supports transactions.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class MVCCTransactionMap<K, V> implements TransactionMap<K, V> {

    /**
     * The map id.
     */
    final int mapId;

    /**
     * If a record was read that was updated by this transaction, and the
     * update occurred before this log id, the older version is read. This
     * is so that changes are not immediately visible, to support statement
     * processing (for example "update test set id = id + 1").
     */
    long readLogId = Long.MAX_VALUE;

    /**
     * The map used for writing (the latest version).
     * <p>
     * Key: key the key of the data.
     * Value: { transactionId, oldVersion, value }
     */
    public final StorageMap<K, VersionedValue> map;

    private final MVCCTransaction transaction;

    MVCCTransactionMap(MVCCTransaction transaction, StorageMap<K, VersionedValue> map, int mapId) {
        this.transaction = transaction;
        this.map = map;
        this.mapId = mapId;
    }

    /**
     * Set the savepoint. Afterwards, reads are based on the specified
     * savepoint.
     *
     * @param savepoint the savepoint
     */
    public void setSavepoint(long savepoint) {
        this.readLogId = savepoint;
    }

    /**
     * Get a clone of this map for the given transaction.
     *
     * @param transaction the transaction
     * @param savepoint the savepoint
     * @return the map
     */
    @Override
    public MVCCTransactionMap<K, V> getInstance(Transaction transaction, long savepoint) {
        MVCCTransactionMap<K, V> m = new MVCCTransactionMap<K, V>((MVCCTransaction) transaction, map, mapId);
        m.setSavepoint(savepoint);
        return m;
    }

    /**
     * Get the size of the raw map. This includes uncommitted entries, and
     * transiently removed entries, so it is the maximum number of entries.
     *
     * @return the maximum size
     */
    @Override
    public long sizeAsLongMax() {
        return map.sizeAsLong();
    }

    /**
     * Get the size of the map as seen by this transaction.
     *
     * @return the size
     */
    @Override
    public long sizeAsLong() {
        long sizeRaw = map.sizeAsLong();
        StorageMap<Long, Object[]> undo = transaction.transactionEngine.undoLog;
        long undoLogSize;
        synchronized (undo) {
            undoLogSize = undo.sizeAsLong();
        }
        if (undoLogSize == 0) {
            return sizeRaw;
        }
        if (undoLogSize > sizeRaw) {
            // the undo log is larger than the map -
            // count the entries of the map
            long size = 0;
            org.lealone.storage.StorageMap.Cursor<K, VersionedValue> cursor = map.cursor(null);
            while (cursor.hasNext()) {
                K key = cursor.next();
                VersionedValue data = cursor.getValue();
                data = getValue(key, readLogId, data);
                if (data != null && data.value != null) {
                    size++;
                }
            }
            return size;
        }
        // the undo log is smaller than the map -
        // scan the undo log and subtract invisible entries
        synchronized (undo) {
            // re-fetch in case any transaction was committed now
            long size = map.sizeAsLong();
            StorageMap<Object, Integer> temp = transaction.transactionEngine.createTempMap();
            try {
                for (Entry<Long, Object[]> e : undo.entrySet()) {
                    Object[] op = e.getValue();
                    int m = (Integer) op[0];
                    if (m != mapId) {
                        // a different map - ignore
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    K key = (K) op[1];
                    if (get(key) == null) {
                        Integer old = temp.put(key, 1);
                        // count each key only once (there might be multiple
                        // changes for the same key)
                        if (old == null) {
                            size--;
                        }
                    }
                }
            } finally {
                //transaction.transactionEngine.store.removeMap(temp);
                temp.remove();
            }
            return size;
        }
    }

    /**
     * Remove an entry.
     * <p>
     * If the row is locked, this method will retry until the row could be
     * updated or until a lock timeout.
     *
     * @param key the key
     * @throws IllegalStateException if a lock timeout occurs
     */
    @Override
    public V remove(K key) {
        return set(key, null);
    }

    /**
     * Update the value for the given key.
     * <p>
     * If the row is locked, this method will retry until the row could be
     * updated or until a lock timeout.
     *
     * @param key the key
     * @param value the new value (not null)
     * @return the old value
     * @throws IllegalStateException if a lock timeout occurs
     */
    @Override
    public V put(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        return set(key, value);
    }

    /**
     * Update the value for the given key, without adding an undo log entry.
     *
     * @param key the key
     * @param value the value
     * @return the old value
     */
    @Override
    @SuppressWarnings("unchecked")
    public V putCommitted(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        VersionedValue newValue = new VersionedValue();
        newValue.value = value;
        VersionedValue oldValue = map.put(key, newValue);
        return (V) (oldValue == null ? null : oldValue.value);
    }

    private V set(K key, V value) {
        transaction.checkNotClosed();
        V old = get(key);
        boolean ok = trySet(key, value, false);
        if (ok) {
            return old;
        }
        throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_LOCKED, "Entry is locked");
    }

    /**
     * Try to remove the value for the given key.
     * <p>
     * This will fail if the row is locked by another transaction (that
     * means, if another open transaction changed the row).
     *
     * @param key the key
     * @return whether the entry could be removed
     */
    public boolean tryRemove(K key) {
        return trySet(key, null, false);
    }

    /**
     * Try to update the value for the given key.
     * <p>
     * This will fail if the row is locked by another transaction (that
     * means, if another open transaction changed the row).
     *
     * @param key the key
     * @param value the new value
     * @return whether the entry could be updated
     */
    public boolean tryPut(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        return trySet(key, value, false);
    }

    /**
     * Try to set or remove the value. When updating only unchanged entries,
     * then the value is only changed if it was not changed after opening
     * the map.
     *
     * @param key the key
     * @param value the new value (null to remove the value)
     * @param onlyIfUnchanged only set the value if it was not changed (by
     *            this or another transaction) since the map was opened
     * @return true if the value was set, false if there was a concurrent
     *         update
     */
    public boolean trySet(K key, V value, boolean onlyIfUnchanged) {
        VersionedValue current = map.get(key);
        if (onlyIfUnchanged) {
            VersionedValue old = getValue(key, readLogId);
            if (!map.areValuesEqual(old, current)) {
                long tx = MVCCTransactionEngine.getTransactionId(current.operationId);
                if (tx == transaction.transactionId) {
                    if (value == null) {
                        // ignore removing an entry
                        // if it was added or changed
                        // in the same statement
                        return true;
                    } else if (current.value == null) {
                        // add an entry that was removed
                        // in the same statement
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
        VersionedValue newValue = new VersionedValue();
        newValue.operationId = MVCCTransactionEngine.getOperationId(transaction.transactionId, transaction.logId);
        newValue.value = value;
        if (current == null) {
            // a new value
            transaction.log(mapId, key, current);
            VersionedValue old = map.putIfAbsent(key, newValue);
            if (old != null) {
                transaction.logUndo();
                return false;
            }
            return true;
        }
        long id = current.operationId;
        if (id == 0) {
            // committed
            transaction.log(mapId, key, current);
            // the transaction is committed:
            // overwrite the value
            if (!map.replace(key, current, newValue)) {
                // somebody else was faster
                transaction.logUndo();
                return false;
            }
            return true;
        }
        int tx = MVCCTransactionEngine.getTransactionId(current.operationId);
        if (tx == transaction.transactionId) {
            // added or updated by this transaction
            transaction.log(mapId, key, current);
            if (!map.replace(key, current, newValue)) {
                // strange, somebody overwrote the value
                // even though the change was not committed
                transaction.logUndo();
                return false;
            }
            return true;
        }

        if (tx % 2 == 1) {
            boolean isValid = transaction.transactionEngine.validateTransaction(transaction.validator, tx, transaction);
            if (isValid) {
                transaction.transactionEngine.commitAfterValidate(tx);
                return trySet(key, value, onlyIfUnchanged);
            }
        }
        // the transaction is not yet committed
        return false;
    }

    /**
     * Get the value for the given key at the time when this map was opened.
     *
     * @param key the key
     * @return the value or null
     */
    @Override
    public V get(K key) {
        return get(key, readLogId);
    }

    /**
     * Get the most recent value for the given key.
     *
     * @param key the key
     * @return the value or null
     */
    @Override
    public V getLatest(K key) {
        return get(key, Long.MAX_VALUE);
    }

    /**
     * Whether the map contains the key.
     *
     * @param key the key
     * @return true if the map contains an entry for this key
     */
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /**
     * Get the value for the given key.
     *
     * @param key the key
     * @param maxLogId the maximum log id
     * @return the value or null
     */
    @SuppressWarnings("unchecked")
    public V get(K key, long maxLogId) {
        VersionedValue data = getValue(key, maxLogId);
        return data == null ? null : (V) data.value;
    }

    /**
     * Whether the entry for this key was added or removed from this
     * session.
     *
     * @param key the key
     * @return true if yes
     */
    @Override
    public boolean isSameTransaction(K key) {
        VersionedValue data = map.get(key);
        if (data == null) {
            // doesn't exist or deleted by a committed transaction
            return false;
        }
        int tx = MVCCTransactionEngine.getTransactionId(data.operationId);
        return tx == transaction.transactionId;
    }

    private VersionedValue getValue(K key, long maxLog) {
        VersionedValue data = map.get(key);
        return getValue(key, maxLog, data);
    }

    /**
     * Get the versioned value for the given key.
     *
     * @param key the key
     * @param maxLog the maximum log id of the entry
     * @param data the value stored in the main map
     * @return the value
     */
    VersionedValue getValue(K key, long maxLog, VersionedValue data) {
        while (true) {
            if (data == null) {
                // doesn't exist or deleted by a committed transaction
                return null;
            }
            long id = data.operationId;
            if (id == 0) {
                // it is committed
                return data;
            }
            int tx = MVCCTransactionEngine.getTransactionId(id);
            if (tx == transaction.transactionId) {
                // added by this transaction
                if (MVCCTransactionEngine.getLogId(id) < maxLog) {
                    return data;
                }
            }

            if (tx % 2 == 1) {
                boolean isValid = transaction.transactionEngine.validateTransaction(transaction.validator, tx,
                        transaction);
                if (isValid) {
                    transaction.transactionEngine.commitAfterValidate(tx);
                    return getValue(key, maxLog, map.get(key));
                }
            }
            // get the value before the uncommitted transaction
            Object[] d;
            synchronized (transaction.transactionEngine.undoLog) {
                d = transaction.transactionEngine.undoLog.get(id);
            }
            if (d == null) {
                // this entry should be committed or rolled back
                // in the meantime (the transaction might still be open)
                // or it might be changed again in a different
                // transaction (possibly one with the same id)
                data = map.get(key);
                if (data != null && data.operationId == id) {
                    // the transaction was not committed correctly
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_CORRUPT,
                            "The transaction log might be corrupt for key {0}", key);
                }
            } else {
                data = (VersionedValue) d[2];
            }
        }
    }

    /**
     * Check whether this map is closed.
     *
     * @return true if closed
     */
    @Override
    public boolean isClosed() {
        return map.isClosed();
    }

    /**
     * Clear the map.
     */
    @Override
    public void clear() {
        // TODO truncate transactionally?
        map.clear();
    }

    /**
     * Get the first key.
     *
     * @return the first key, or null if empty
     */
    @Override
    public K firstKey() {
        Iterator<K> it = keyIterator(null);
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Get the last key.
     *
     * @return the last key, or null if empty
     */
    @Override
    public K lastKey() {
        K k = map.lastKey();
        while (true) {
            if (k == null) {
                return null;
            }
            if (get(k) != null) {
                return k;
            }
            k = map.lowerKey(k);
        }
    }

    /**
     * Get the smallest key that is larger than the given key, or null if no
     * such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    @Override
    public K higherKey(K key) {
        while (true) {
            K k = map.higherKey(key);
            if (k == null || get(k) != null) {
                return k;
            }
            key = k;
        }
    }

    /**
     * Get one of the previous or next keys. There might be no value
     * available for the returned key.
     *
     * @param key the key (may not be null)
     * @param offset how many keys to skip (-1 for previous, 1 for next)
     * @return the key
     */
    @Override
    public K relativeKey(K key, long offset) {
        K k = offset > 0 ? map.ceilingKey(key) : map.floorKey(key);
        if (k == null) {
            return k;
        }
        long index = map.getKeyIndex(k);
        return map.getKey(index + offset);
    }

    /**
     * Get the largest key that is smaller than the given key, or null if no
     * such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    @Override
    public K lowerKey(K key) {
        while (true) {
            K k = map.lowerKey(key);
            if (k == null || get(k) != null) {
                return k;
            }
            key = k;
        }
    }

    /**
     * Iterate over keys.
     *
     * @param from the first key to return
     * @return the iterator
     */
    @Override
    public Iterator<K> keyIterator(K from) {
        return keyIterator(from, false);
    }

    /**
     * Iterate over keys.
     *
     * @param from the first key to return
     * @param includeUncommitted whether uncommitted entries should be
     *            included
     * @return the iterator
     */
    @Override
    public Iterator<K> keyIterator(final K from, final boolean includeUncommitted) {
        return new Iterator<K>() {
            private K currentKey = from;
            private org.lealone.storage.StorageMap.Cursor<K, VersionedValue> cursor = map.cursor(currentKey);

            {
                fetchNext();
            }

            private void fetchNext() {
                while (cursor.hasNext()) {
                    K k;
                    try {
                        k = cursor.next();
                    } catch (IllegalStateException e) {
                        // TODO this is a bit ugly
                        if (DataUtils.getErrorCode(e.getMessage()) == DataUtils.ERROR_CHUNK_NOT_FOUND) {
                            cursor = map.cursor(currentKey);
                            // we (should) get the current key again,
                            // we need to ignore that one
                            if (!cursor.hasNext()) {
                                break;
                            }
                            cursor.next();
                            if (!cursor.hasNext()) {
                                break;
                            }
                            k = cursor.next();
                        } else {
                            throw e;
                        }
                    }
                    currentKey = k;
                    if (includeUncommitted) {
                        return;
                    }
                    if (containsKey(k)) {
                        return;
                    }
                }
                currentKey = null;
            }

            @Override
            public boolean hasNext() {
                return currentKey != null;
            }

            @Override
            public K next() {
                K result = currentKey;
                fetchNext();
                return result;
            }

            @Override
            public void remove() {
                throw DataUtils.newUnsupportedOperationException("Removing is not supported");
            }
        };
    }

    /**
     * Iterate over entries.
     *
     * @param from the first key to return
     * @return the iterator
     */
    @Override
    public Iterator<Entry<K, V>> entryIterator(final K from) {
        return new Iterator<Entry<K, V>>() {
            private Entry<K, V> current;
            private K currentKey = from;
            private org.lealone.storage.StorageMap.Cursor<K, VersionedValue> cursor = map.cursor(currentKey);

            {
                fetchNext();
            }

            private void fetchNext() {
                while (cursor.hasNext()) {
                    K k;
                    try {
                        k = cursor.next();
                    } catch (IllegalStateException e) {
                        // TODO this is a bit ugly
                        if (DataUtils.getErrorCode(e.getMessage()) == DataUtils.ERROR_CHUNK_NOT_FOUND) {
                            cursor = map.cursor(currentKey);
                            // we (should) get the current key again,
                            // we need to ignore that one
                            if (!cursor.hasNext()) {
                                break;
                            }
                            cursor.next();
                            if (!cursor.hasNext()) {
                                break;
                            }
                            k = cursor.next();
                        } else {
                            throw e;
                        }
                    }
                    final K key = k;
                    VersionedValue data = cursor.getValue();
                    data = getValue(key, readLogId, data);
                    if (data != null && data.value != null) {
                        @SuppressWarnings("unchecked")
                        final V value = (V) data.value;
                        current = new DataUtils.MapEntry<K, V>(key, value);
                        currentKey = key;
                        return;
                    }
                }
                current = null;
                currentKey = null;
            }

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public Entry<K, V> next() {
                Entry<K, V> result = current;
                fetchNext();
                return result;
            }

            @Override
            public void remove() {
                throw DataUtils.newUnsupportedOperationException("Removing is not supported");
            }
        };

    }

    /**
     * Iterate over keys.
     *
     * @param iterator the iterator to wrap
     * @param includeUncommitted whether uncommitted entries should be
     *            included
     * @return the iterator
     */
    public Iterator<K> wrapIterator(final Iterator<K> iterator, final boolean includeUncommitted) {
        // TODO duplicate code for wrapIterator and entryIterator
        return new Iterator<K>() {
            private K current;

            {
                fetchNext();
            }

            private void fetchNext() {
                while (iterator.hasNext()) {
                    current = iterator.next();
                    if (includeUncommitted) {
                        return;
                    }
                    if (containsKey(current)) {
                        return;
                    }
                }
                current = null;
            }

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public K next() {
                K result = current;
                fetchNext();
                return result;
            }

            @Override
            public void remove() {
                throw DataUtils.newUnsupportedOperationException("Removing is not supported");
            }
        };
    }

    @Override
    public DataType getKeyType() {
        return map.getKeyType();
    }

    @Override
    public void setVolatile(boolean isVolatile) {
        map.setVolatile(isVolatile);
    }

    @Override
    public void removeMap() {
        map.remove();
    }

    @Override
    public int getMapId() {
        return mapId;
    }
}
