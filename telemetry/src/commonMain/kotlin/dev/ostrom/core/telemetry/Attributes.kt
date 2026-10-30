package dev.ostrom.core.telemetry

/**
 * The value of one telemetry attribute. OpenTelemetry allows more shapes (arrays, maps, bytes); Ostrom only emits
 * these four, which every backend (Tempo, Mimir, Loki, Langfuse) indexes.
 */
public sealed interface AttributeValue {
    /** A text value. */
    public data class Text(val value: String) : AttributeValue

    /** A whole number. */
    public data class Integer(val value: Long) : AttributeValue

    /** A floating-point number. */
    public data class Decimal(val value: Double) : AttributeValue

    /** A boolean. */
    public data class Flag(val value: Boolean) : AttributeValue
}

/** One attribute: a dotted key (`http.route`) and its value. */
public data class Attribute(val key: String, val value: AttributeValue)

/**
 * An ordered, immutable set of attributes. On a metric, attributes identify a series, so they must come from a bounded
 * set (an enum, a route template), never an identifier (ENG-003); [Attributes.key] is what the meter groups on.
 */
public class Attributes private constructor(public val entries: List<Attribute>) {
    /** A stable text identity of the set, used to group metric points; equal sets give equal keys. */
    public val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.sortedBy { it.key }.joinToString(separator = "\u0001") { "${it.key}\u0002${it.value}" }
    }

    /** True when the set holds no attribute. */
    public fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Reads one attribute.
     *
     * @param key The attribute key.
     * @return Its value, or null when absent.
     */
    public operator fun get(key: String): AttributeValue? = entries.firstOrNull { it.key == key }?.value

    /**
     * Adds the attributes of [other], which win on a shared key.
     *
     * @param other The attributes to add.
     * @return A new set.
     */
    public operator fun plus(other: Attributes): Attributes {
        if (other.isEmpty()) return this
        val overridden = other.entries.mapTo(HashSet()) { it.key }
        return Attributes(entries.filterNot { it.key in overridden } + other.entries)
    }

    override fun equals(other: Any?): Boolean = other is Attributes && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }

    /** Builds a set; a key set twice keeps the last value. */
    public class Builder {
        private val values = LinkedHashMap<String, AttributeValue>()

        /** Sets a text attribute. @return This builder. */
        public fun put(key: String, value: String): Builder = apply { values[key] = AttributeValue.Text(value) }

        /** Sets a whole-number attribute. @return This builder. */
        public fun put(key: String, value: Long): Builder = apply { values[key] = AttributeValue.Integer(value) }

        /** Sets a whole-number attribute. @return This builder. */
        public fun put(key: String, value: Int): Builder = put(key, value.toLong())

        /** Sets a floating-point attribute. @return This builder. */
        public fun put(key: String, value: Double): Builder = apply { values[key] = AttributeValue.Decimal(value) }

        /** Sets a boolean attribute. @return This builder. */
        public fun put(key: String, value: Boolean): Builder = apply { values[key] = AttributeValue.Flag(value) }

        /** Sets an attribute of any supported type. @return This builder. */
        public fun put(attribute: Attribute): Builder = apply { values[attribute.key] = attribute.value }

        /** @return The set built so far. */
        public fun build(): Attributes =
            if (values.isEmpty()) EMPTY else Attributes(values.map { (key, value) -> Attribute(key, value) })
    }

    /** Factories. */
    public companion object {
        /** The empty set, shared. */
        public val EMPTY: Attributes = Attributes(emptyList())

        /**
         * Builds a set from text pairs, the common case for metric attributes.
         *
         * @param pairs Key and value pairs.
         * @return The set.
         */
        public fun of(vararg pairs: Pair<String, String>): Attributes =
            Builder().apply { pairs.forEach { (key, value) -> put(key, value) } }.build()
    }
}

/**
 * Builds an attribute set.
 *
 * @param block Fills the builder.
 * @return The set.
 */
public fun attributes(block: Attributes.Builder.() -> Unit): Attributes = Attributes.Builder().apply(block).build()
