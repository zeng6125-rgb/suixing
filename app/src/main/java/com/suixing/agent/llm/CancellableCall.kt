package com.suixing.agent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Keeps cancellation attached until the response body has finished being consumed. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                // 取消之后回调仍可能被投递：此时 continuation 已结束，再 resumeWith 会抛
                // `IllegalStateException("Already resumed")`，而且是从 OkHttp 的 dispatcher
                // 线程抛出、不在任何 runCatching 内 ⇒ 直接进 UEH。全仓既有约定（其余 5 个
                // `suspendCancellableCoroutine` 文件共 17 处）都是先查 isActive。
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    // 取消后仍执行完整流读取没有任何消费者，直接丢弃响应体。
                    response.close()
                    return
                }
                // Reading and delivery are separate: a delivery exception must never trigger
                // a second resume of the same continuation.
                val result = try {
                    Result.success(response.use(read))
                } catch (cancel: CancellationException) {
                    continuation.cancel(cancel)
                    return
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                continuation.resumeWith(result)
            }
        })
    }
