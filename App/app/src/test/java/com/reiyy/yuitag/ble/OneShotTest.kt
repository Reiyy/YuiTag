package com.reiyy.yuitag.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneShotTest {

    @Test
    fun testNormalAwaitAndSignal() = runBlocking {
        val oneShot = BleGattClient.OneShot<Int>(defaultTimeoutMs = 1000L)
        launch {
            delay(50)
            oneShot.signal(42)
        }
        val result = oneShot.await()
        assertEquals(42, result)
    }

    @Test
    fun testBufferedSignalBeforeAwait() = runBlocking {
        val oneShot = BleGattClient.OneShot<String>(defaultTimeoutMs = 1000L)
        oneShot.signal("buffered_value")
        val result = oneShot.await()
        assertEquals("buffered_value", result)
    }

    @Test
    fun testAwaitTimeoutReturnsNull() = runBlocking {
        val oneShot = BleGattClient.OneShot<Int>(defaultTimeoutMs = 50L)
        val result = oneShot.await()
        assertNull(result)
    }

    @Test
    fun testAwaitMatchingFiltersStaleSignal() = runBlocking {
        val oneShot = BleGattClient.OneShot<ByteArray>(defaultTimeoutMs = 1000L)
        // Signal stale packet (Seq = 0x01)
        oneShot.signal(byteArrayOf(0x01, 0x0A))

        launch {
            delay(50)
            // Signal matching packet (Seq = 0x02)
            oneShot.signal(byteArrayOf(0x02, 0x0A))
        }

        val result = oneShot.awaitMatching(timeoutMs = 1000L) { packet ->
            YuiTagProtocol.extractSeq(packet) == 0x02
        }

        assertEquals(0x02, result?.let { YuiTagProtocol.extractSeq(it) })
    }

    @Test
    fun testCancellationResetsActiveState() = runBlocking {
        val oneShot = BleGattClient.OneShot<Int>(defaultTimeoutMs = 5000L)
        val job = launch {
            oneShot.await()
        }
        delay(20)
        job.cancel()
        job.join()

        // Subsequent await should work cleanly without getting stuck
        launch {
            delay(30)
            oneShot.signal(99)
        }
        val result = oneShot.await()
        assertEquals(99, result)
    }
}
