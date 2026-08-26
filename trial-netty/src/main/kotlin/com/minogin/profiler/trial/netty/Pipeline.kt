package com.minogin.profiler.trial.netty

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import java.nio.charset.StandardCharsets

/**
 * The request as it travels the pipeline. One per request, mutated in place by each handler.
 *
 * Netty passes one object down the chain and handlers annotate it, which is what real pipelines do
 * and is also why the *stack* cannot say which handler produced a given cost: every one of them is
 * reached through the same `AbstractChannelHandlerContext.invokeChannelRead`.
 */
class Exchange(val request: FullHttpRequest) {
    var tenant: String? = null
    var route: String? = null
    var rejected: HttpResponseStatus? = null
    var scoreAccum: Long = 0
    val tags = ArrayList<String>(4)
}

/**
 * A policy in the chain — and the reason this trial exists in the shape it does.
 *
 * There are several of these in the pipeline and **they are all the same class**. A flame graph sees
 * `PolicyHandler.channelRead` once, with everybody's time in it, exactly as Lucene's four
 * `TermQuery` clauses shared `TermScorer`. The difference the domain cares about is which *policy*,
 * and the stack does not have it.
 *
 * This is not a contrivance: a chain of configured policy objects sharing one implementation is how
 * gateways, filter chains and rule engines are ordinarily written.
 */
class PolicyHandler(
    val policy: String,
    /** Which header this policy inspects. */
    private val header: String,
    /** How many of the request's bytes it hashes before deciding — the policy's real cost. */
    private val depth: Int,
) : SimpleChannelInboundHandler<Exchange>(false) {

    /** Set by the trial when labels are placed. -1 means no label. */
    @JvmField var opId: Int = -1

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        run(ex)
        ctx.fireChannelRead(ex)
    }

    /** Split out so a label can wrap the work without wrapping the forwarding. */
    fun run(ex: Exchange) {
        if (ex.rejected != null) return
        val value = ex.request.headers().get(header) ?: DEFAULT
        // Real work with a real frame: a rolling hash over as much of the value and the body as
        // this policy is configured to look at. Deterministic, so two runs do the same work.
        var h = 0x811C9DC5L
        val body = ex.request.content()
        val n = minOf(depth, body.readableBytes())
        for (i in 0 until value.length) h = (h xor value[i].code.toLong()) * 0x01000193L
        for (i in 0 until n) h = (h xor body.getByte(i).toLong()) * 0x01000193L
        ex.scoreAccum += h
        if (h and 0xFFFFL == 0L) {
            ex.rejected = HttpResponseStatus.FORBIDDEN
            ex.tags.add(policy)
        }
    }

    private companion object {
        const val DEFAULT = "-"
    }
}

/**
 * Distinct classes, deliberately, so the trial has a control.
 *
 * A flame graph *can* name these — they are their own frames — and the point of including them is
 * to show which parts of a pipeline the existing tools already answer. Whatever the labels add here
 * is not a difference the tool invented, and [case.md](../../../../../../../docs/case.md) keeps an
 * honest section on exactly that.
 */
class AuthHandler : SimpleChannelInboundHandler<Exchange>(false) {
    @JvmField var opId: Int = -1

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        run(ex)
        ctx.fireChannelRead(ex)
    }

    fun run(ex: Exchange) {
        val auth = ex.request.headers().get(HttpHeaderNames.AUTHORIZATION)
        if (auth == null || !auth.startsWith("Bearer ")) {
            ex.rejected = HttpResponseStatus.UNAUTHORIZED
            return
        }
        // Decoding the token is the expensive half, and it is why auth is not free in real gateways.
        val token = auth.substring(7)
        var acc = 0L
        for (i in token.indices) acc = acc * 31 + token[i].code
        ex.tenant = TENANTS[((acc ushr 8) and 0xF).toInt()]
    }

    private companion object {
        val TENANTS = Array(16) { "tenant-$it" }
    }
}

/** Distinct class: turns a path into a route name. The other half of the control. */
class RouteHandler : SimpleChannelInboundHandler<Exchange>(false) {
    @JvmField var opId: Int = -1

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        run(ex)
        ctx.fireChannelRead(ex)
    }

    fun run(ex: Exchange) {
        if (ex.rejected != null) return
        val uri = ex.request.uri()
        // A linear scan over a route table, which is what a small router really does.
        for (r in ROUTES) {
            if (uri.regionMatches(0, r, 0, r.length)) {
                ex.route = r
                return
            }
        }
        ex.route = "/other"
    }

    private companion object {
        val ROUTES = arrayOf(
            "/api/orders", "/api/customers", "/api/inventory", "/api/pricing",
            "/api/shipments", "/api/returns", "/api/audit", "/api/health",
        )
    }
}

/** Terminal handler: builds and writes the response. Distinct class, so nameable from a stack. */
class RenderHandler : SimpleChannelInboundHandler<Exchange>(false) {
    @JvmField var opId: Int = -1

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        val response = run(ex)
        ex.request.release()
        ctx.writeAndFlush(response)
    }

    fun run(ex: Exchange): DefaultFullHttpResponse {
        val status = ex.rejected ?: HttpResponseStatus.OK
        val sb = StringBuilder(96)
        sb.append("{\"route\":\"").append(ex.route ?: "-")
            .append("\",\"tenant\":\"").append(ex.tenant ?: "-")
            .append("\",\"score\":").append(ex.scoreAccum)
        if (ex.tags.isNotEmpty()) {
            sb.append(",\"blocked\":\"").append(ex.tags[0]).append('"')
        }
        sb.append('}')
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        return response
    }
}

/** Adapts Netty's `FullHttpRequest` into the [Exchange] the rest of the chain speaks. */
class ExchangeHandler : SimpleChannelInboundHandler<FullHttpRequest>(false) {
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        ctx.fireChannelRead(Exchange(request))
    }
}
