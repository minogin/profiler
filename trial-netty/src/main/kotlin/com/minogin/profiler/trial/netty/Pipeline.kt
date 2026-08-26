package com.minogin.profiler.trial.netty

import com.minogin.profiler.op
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
 * Where every label in this pipeline goes, and the one decision that matters about it.
 *
 * The label wraps the handler's **own work** and never the `fireChannelRead` below it. A stack gives
 * inclusive time, and on a chain inclusive time contains every later handler — which is why
 * qualification found a dozen frames between 82% and 99% and none of them meaning anything. Self
 * time per handler is the number that was missing, and wrapping only `run` is what produces it.
 *
 * The `opId >= 0` branch is how the unlabelled configuration is measured against this one. It is a
 * final field on an object that lives for the connection, so it predicts perfectly and the two
 * configurations differ by a hook rather than by a branch.
 */
private inline fun labelled(opId: Int, body: () -> Unit) {
    if (opId >= 0) op(opId) { body() } else body()
}

/**
 * A policy in the chain — and the reason this trial exists in the shape it does.
 *
 * There are four of these in the pipeline and **they are all the same class**. A flame graph sees
 * `PolicyHandler.run` once, with everybody's time in it — measured at 60.30% for the four together,
 * exactly as Lucene's four `TermQuery` clauses shared `TermScorer`. The difference the domain cares
 * about is *which policy*, and the stack does not have it.
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
    /**
     * The label for this policy, or -1 when the trial runs unlabelled.
     *
     * Passed in at construction rather than assigned per object. Netty builds a fresh pipeline for
     * every connection, so with sixteen connections there are sixteen `PolicyHandler` instances per
     * policy — the id belongs to the *policy*, and all sixteen share it. Getting this wrong would
     * produce sixteen labels reading a sixteenth each, which is the sort of thing that looks like a
     * finding.
     */
    @JvmField val opId: Int,
) : SimpleChannelInboundHandler<Exchange>(false) {

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        labelled(opId) { run(ex) }
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
 * is not a difference the tool invented, and case.md keeps an honest section on exactly that.
 */
class AuthHandler(@JvmField val opId: Int) : SimpleChannelInboundHandler<Exchange>(false) {

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        labelled(opId) { run(ex) }
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
class RouteHandler(@JvmField val opId: Int) : SimpleChannelInboundHandler<Exchange>(false) {

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        labelled(opId) { run(ex) }
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

/**
 * Terminal handler: builds and writes the response. Distinct class, so nameable from a stack.
 *
 * The label covers building the response and **not** `writeAndFlush`, which hands the buffer to
 * Netty's outbound chain and the socket. Where that boundary sits is a judgement, and it is the one
 * place in this pipeline where the label could reasonably have been drawn elsewhere: putting it
 * around the write as well would bill the socket to this handler.
 */
class RenderHandler(@JvmField val opId: Int) : SimpleChannelInboundHandler<Exchange>(false) {

    override fun channelRead0(ctx: ChannelHandlerContext, ex: Exchange) {
        var response: DefaultFullHttpResponse? = null
        labelled(opId) { response = run(ex) }
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
