package com.bitchat.android.protocol

/**
 * Lock-guarded collections for shared code (Collections.synchronized* and CopyOnWriteArrayList are
 * JVM-only). Every operation is atomic. Iterators walk a snapshot taken under the lock, so a caller
 * can iterate while other threads mutate, with the copy-on-write semantics of CopyOnWriteArrayList.
 */
fun <T> synchronizedSetOf(): MutableSet<T> = SynchronizedSet()

fun <K, V> synchronizedMapOf(): MutableMap<K, V> = SynchronizedMap()

fun <T> synchronizedListOf(): MutableList<T> = SynchronizedList()

private class SynchronizedSet<T> : MutableSet<T> {
    private val lock = PlatformLock()
    private val delegate = LinkedHashSet<T>()

    override val size: Int get() = lock.withLock { delegate.size }
    override fun isEmpty(): Boolean = lock.withLock { delegate.isEmpty() }
    override fun contains(element: T): Boolean = lock.withLock { delegate.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = lock.withLock { delegate.containsAll(elements) }
    override fun add(element: T): Boolean = lock.withLock { delegate.add(element) }
    override fun addAll(elements: Collection<T>): Boolean = lock.withLock { delegate.addAll(elements) }
    override fun remove(element: T): Boolean = lock.withLock { delegate.remove(element) }
    override fun removeAll(elements: Collection<T>): Boolean = lock.withLock { delegate.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<T>): Boolean = lock.withLock { delegate.retainAll(elements.toSet()) }
    override fun clear() = lock.withLock { delegate.clear() }
    override fun iterator(): MutableIterator<T> = SnapshotIterator(lock.withLock { delegate.toList() }) { remove(it) }
    override fun toString(): String = lock.withLock { delegate.toString() }
}

private class SynchronizedList<T> : MutableList<T> {
    private val lock = PlatformLock()
    private val delegate = ArrayList<T>()

    override val size: Int get() = lock.withLock { delegate.size }
    override fun isEmpty(): Boolean = lock.withLock { delegate.isEmpty() }
    override fun contains(element: T): Boolean = lock.withLock { delegate.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = lock.withLock { delegate.containsAll(elements) }
    override fun get(index: Int): T = lock.withLock { delegate[index] }
    override fun indexOf(element: T): Int = lock.withLock { delegate.indexOf(element) }
    override fun lastIndexOf(element: T): Int = lock.withLock { delegate.lastIndexOf(element) }
    override fun add(element: T): Boolean = lock.withLock { delegate.add(element) }
    override fun add(index: Int, element: T) = lock.withLock { delegate.add(index, element) }
    override fun addAll(elements: Collection<T>): Boolean = lock.withLock { delegate.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<T>): Boolean = lock.withLock { delegate.addAll(index, elements) }
    override fun remove(element: T): Boolean = lock.withLock { delegate.remove(element) }
    override fun removeAt(index: Int): T = lock.withLock { delegate.removeAt(index) }
    override fun removeAll(elements: Collection<T>): Boolean = lock.withLock { delegate.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<T>): Boolean = lock.withLock { delegate.retainAll(elements.toSet()) }
    override fun set(index: Int, element: T): T = lock.withLock { delegate.set(index, element) }
    override fun clear() = lock.withLock { delegate.clear() }
    override fun iterator(): MutableIterator<T> = SnapshotIterator(lock.withLock { delegate.toList() }) { remove(it) }
    override fun listIterator(): MutableListIterator<T> = snapshotList().listIterator()
    override fun listIterator(index: Int): MutableListIterator<T> = snapshotList().listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = snapshotList().subList(fromIndex, toIndex)
    override fun toString(): String = lock.withLock { delegate.toString() }

    /** Read-only snapshot; structural changes through it do not affect this list. */
    private fun snapshotList(): MutableList<T> = lock.withLock { ArrayList(delegate) }
}

private class SynchronizedMap<K, V> : MutableMap<K, V> {
    private val lock = PlatformLock()
    private val delegate = LinkedHashMap<K, V>()

    override val size: Int get() = lock.withLock { delegate.size }
    override fun isEmpty(): Boolean = lock.withLock { delegate.isEmpty() }
    override fun containsKey(key: K): Boolean = lock.withLock { delegate.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.withLock { delegate.containsValue(value) }
    override fun get(key: K): V? = lock.withLock { delegate[key] }
    override fun put(key: K, value: V): V? = lock.withLock { delegate.put(key, value) }
    override fun putAll(from: Map<out K, V>) = lock.withLock { delegate.putAll(from) }
    override fun remove(key: K): V? = lock.withLock { delegate.remove(key) }
    override fun clear() = lock.withLock { delegate.clear() }

    /** Snapshots: iterate freely, mutate through the map itself. */
    override val keys: MutableSet<K> get() = lock.withLock { LinkedHashSet(delegate.keys) }
    override val values: MutableCollection<V> get() = lock.withLock { ArrayList(delegate.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock { LinkedHashMap(delegate).entries }

    override fun toString(): String = lock.withLock { delegate.toString() }
}

private class SnapshotIterator<T>(
    private val snapshot: List<T>,
    private val removeFromSource: (T) -> Unit
) : MutableIterator<T> {
    private var index = 0
    private var last: T? = null
    private var canRemove = false

    override fun hasNext(): Boolean = index < snapshot.size

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        return snapshot[index++].also { last = it; canRemove = true }
    }

    @Suppress("UNCHECKED_CAST")
    override fun remove() {
        check(canRemove) { "next() has not been called" }
        removeFromSource(last as T)
        canRemove = false
    }
}
