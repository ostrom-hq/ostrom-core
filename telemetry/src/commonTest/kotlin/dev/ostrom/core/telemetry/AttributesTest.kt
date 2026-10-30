// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttributesTest {
    @Test
    fun theBuilderKeepsEveryTypeAndTheLastValueOfAKey() {
        val set = attributes {
            put("text", "a")
            put("long", 2L)
            put("int", 3)
            put("double", 1.5)
            put("flag", true)
            put(Attribute("text", AttributeValue.Text("b")))
        }

        assertEquals(AttributeValue.Text("b"), set["text"])
        assertEquals(AttributeValue.Integer(2), set["long"])
        assertEquals(AttributeValue.Integer(3), set["int"])
        assertEquals(AttributeValue.Decimal(1.5), set["double"])
        assertEquals(AttributeValue.Flag(true), set["flag"])
        assertNull(set["absent"])
        assertEquals(listOf("text", "long", "int", "double", "flag"), set.entries.map { it.key })
    }

    @Test
    fun anEmptyBuilderGivesTheSharedEmptySet() {
        assertSame(Attributes.EMPTY, attributes { })
        assertTrue(Attributes.EMPTY.isEmpty())
        assertEquals("{}", Attributes.EMPTY.toString())
    }

    @Test
    fun setsWithTheSameEntriesInAnotherOrderAreEqual() {
        val first = Attributes.of("a" to "1", "b" to "2")
        val second = Attributes.of("b" to "2", "a" to "1")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.key, second.key)
        assertNotEquals(first, Attributes.of("a" to "1"))
        assertFalse(first.equals("a=1"))
    }

    @Test
    fun aTextAndANumberWithTheSameDigitsAreDifferentSeries() {
        assertNotEquals(attributes { put("code", "200") }, attributes { put("code", 200) })
    }

    @Test
    fun plusLetsTheRightSideWinOnASharedKey() {
        val left = Attributes.of("a" to "1", "b" to "2")
        val right = Attributes.of("b" to "3", "c" to "4")

        val merged = left + right

        assertEquals(Attributes.of("a" to "1", "b" to "3", "c" to "4"), merged)
        assertSame(left, left + Attributes.EMPTY)
        assertEquals("{a=Text(value=1), b=Text(value=3), c=Text(value=4)}", merged.toString())
    }

    @Test
    fun withoutDropsKeysAndKeepsTheSameSetWhenThereIsNothingToDrop() {
        val set = Attributes.of("a" to "1", "b" to "2")

        assertEquals(Attributes.of("b" to "2"), set.without(setOf("a", "z")))
        assertSame(set, set.without(setOf("z")))
        assertNotEquals(set, Attributes.of("a" to "1", "c" to "2"))
    }
}
