package com.suixing.agent.llm

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableCallTest {
    @Test fun cancellationCancelsOnlyItsOwnCallWhileBodyReadIsBlocked() = runBlocking {
        val client = OkHttpClient()
        ServerSocket(0).use { server ->
            val startedReading = CountDownLatch(1)
            val readerExited = CountDownLatch(1)
            val peerDone = CountDownLatch(1)
            Thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
                            flush()
                        }
                        peerDone.await(5, TimeUnit.SECONDS)
                    }
                } finally { peerDone.countDown() }
            }.apply { isDaemon = true; start() }
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}").build()
            val call = client.newCall(request)
            val other = client.newCall(request)
            try {
                val job = launch(Dispatchers.IO) {
                    call.readCancellable { response ->
                        startedReading.countDown()
                        try { response.body!!.string() } finally { readerExited.countDown() }
                    }
                }
                assertTrue(withContext(Dispatchers.IO) { startedReading.await(3, TimeUnit.SECONDS) })
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertFalse(other.isCanceled())
                assertTrue(withContext(Dispatchers.IO) { readerExited.await(2, TimeUnit.SECONDS) })
            } finally {
                peerDone.countDown()
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
