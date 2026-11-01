package com.suixing.agent.llm

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取消之后**回调仍然到达**的分支（issue #71 A1）。
 *
 * 这是全仓最容易崩回去的一条：守卫删掉照样编译、现有单测照样绿，只有真机点停止撞上时序才
 * 抛 `IllegalStateException("Already resumed")`，而它来自 OkHttp 的 dispatcher 线程、
 * 不在任何 runCatching 内 ⇒ 直接进 UEH 崩进程。
 */
class CancellableCallCancelledCallbackTest {

    /** 只把回调收起来、由测试决定何时投递的假 Call（其余成员委托给真 Call）。 */
    private class FakeCall(private val real: Call) : Call by real {
        var callback: Callback? = null
        val cancelled = AtomicBoolean(false)
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() {
            cancelled.set(true)
            real.cancel()
        }
        override fun isCanceled(): Boolean = cancelled.get()
    }

    private fun response(request: Request, closed: AtomicBoolean): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(object : okhttp3.ResponseBody() {
                override fun contentType() = "text/plain".toMediaTypeOrNull()
                override fun contentLength(): Long = 0
                override fun source() = okio.Buffer()
                override fun close() { closed.set(true) }
            })
            .build()

    @Test
    fun `response arriving after cancellation is closed and never resumed`() = runBlocking {
        val request = Request.Builder().url("http://127.0.0.1/").build()
        val call = FakeCall(OkHttpClient().newCall(request))
        val closed = AtomicBoolean(false)
        var readRan = false

        val job = launch { call.readCancellable { readRan = true } }
        // 让 readCancellable 走到将回调登记到 FakeCall
        while (call.callback == null) yield()
        job.cancelAndJoin()
        assertTrue("取消必须真的 cancel 底层 call", call.cancelled.get())

        // 取消之后 OkHttp 仍可能投递响应：守卫生效 ⇒ 不抛、不读、关掉响应体
        call.callback!!.onResponse(call, response(request, closed))
        assertTrue("取消后到达的响应必须被关闭", closed.get())
        assertFalse("取消后不得再执行读取", readRan)
    }

    @Test
    fun `failure arriving after cancellation is swallowed`() = runBlocking {
        val request = Request.Builder().url("http://127.0.0.1/").build()
        val call = FakeCall(OkHttpClient().newCall(request))
        val job = launch { call.readCancellable { } }
        while (call.callback == null) yield()
        job.cancelAndJoin()

        // 这一步在旧实现里会 resumeWith ⇒ IllegalStateException —— 用例失败即回归
        call.callback!!.onFailure(call, IOException("cancelled"))
        assertTrue(call.cancelled.get())
    }
}
