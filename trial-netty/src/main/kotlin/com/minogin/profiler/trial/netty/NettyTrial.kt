package com.minogin.profiler.trial.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import com.minogin.profiler.trial.analyzeJfr
import com.minogin.profiler.trial.recordExecutionSamples
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Trial 3 — Netty.
 *
 * The concurrency shape is the opposite of Lucene's and that is the point. Lucene gave us eight
 * worker threads each running the same work flat out; Netty gives a handful of long-lived event
 * loops carrying many short tasks, with time that is mostly *not* CPU. It is the first foreign
 * workload where the thread-state column has anything to say — and the first that can show it
 * saying the wrong thing, since a selector wait happens in native code and a thread in native code
 * reads `RUNNABLE`.
 */

/** How many policy handlers share one class in the chain. See [PolicyHandler]. */
private val POLICIES = arrayOf(
    Triple("policy:geo", "X-Geo", 24),
    Triple("policy:quota", "X-Quota", 256),
    Triple("policy:abuse", "X-Client", 1024),
    Triple("policy:experiment", "X-Exp", 48),
)

class Server(private val threads: Int, val labelled: Boolean) {
    private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory())

    /** Every handler instance the pipeline builds, so the trial can label and inspect them. */
    val policies = ArrayList<PolicyHandler>()

    lateinit var address: InetSocketAddress
        private set

    fun start(): Server {
        val bootstrap = ServerBootstrap()
            .group(boss, workers)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()
                    p.addLast(HttpServerCodec())
                    p.addLast(HttpObjectAggregator(64 * 1024))
                    p.addLast(ExchangeHandler())
                    p.addLast(AuthHandler())
                    p.addLast(RouteHandler())
                    for ((name, header, depth) in POLICIES) {
                        val h = PolicyHandler(name, header, depth)
                        synchronized(policies) { policies.add(h) }
                        p.addLast(h)
                    }
                    p.addLast(RenderHandler())
                }
            })
        val ch = bootstrap.bind(InetSocketAddress("127.0.0.1", 0)).sync().channel()
        address = ch.localAddress() as InetSocketAddress
        return this
    }

    fun stop() {
        boss.shutdownGracefully()
        workers.shutdownGracefully()
    }
}

/**
 * The load generator, in the same JVM and on its own event loops.
 *
 * Same process on purpose: a separate client would put the measurement at the mercy of another
 * JVM's warm-up and another scheduler's decisions, and the thing under test is the server's
 * handlers rather than the network. The cost is that the client's own threads are in the same
 * process — they are excluded by name where that matters, and the report says how many slots it saw.
 */
class Client(private val target: InetSocketAddress, private val connections: Int, private val inFlight: Int) {
    private val group = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
    val responses = AtomicLong()
    private val channels = ArrayList<Channel>()

    @Volatile private var running = true

    fun start(): Client {
        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpClientCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(64 * 1024))
                    ch.pipeline().addLast(Driver())
                }
            })
        repeat(connections) { i ->
            val ch = bootstrap.connect(target).sync().channel()
            channels.add(ch)
            // Prime the connection: `inFlight` requests outstanding, one more sent per response,
            // so the depth is constant and the server is never idle waiting for the client.
            repeat(inFlight) { ch.write(request(i * inFlight + it)) }
            ch.flush()
        }
        return this
    }

    fun stop() {
        running = false
        channels.forEach { it.close() }
        group.shutdownGracefully()
    }

    private inner class Driver : SimpleChannelInboundHandler<FullHttpResponse>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
            responses.incrementAndGet()
            if (running) ctx.writeAndFlush(request(responses.get().toInt()))
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            if (running) System.err.println("client: ${cause.javaClass.simpleName}: ${cause.message}")
            ctx.close()
        }
    }

    private companion object {
        val ROUTES = arrayOf(
            "/api/orders/", "/api/customers/", "/api/inventory/", "/api/pricing/",
            "/api/shipments/", "/api/returns/", "/api/audit/", "/nowhere/",
        )
        val BODIES = Array(16) { n ->
            // Bodies of different lengths, since the policies hash a configured prefix of the body
            // and a constant length would make three of the four cost the same.
            val r = Random(1000L + n)
            ByteArray(128 + n * 128) { (r.nextInt(26) + 97).toByte() }
        }

        /** A deterministic request, varied by index so the route table and policies see spread. */
        fun request(i: Int): DefaultFullHttpRequest {
            val body = BODIES[i and 15]
            val req = DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST,
                ROUTES[i and 7] + (i and 1023),
                Unpooled.wrappedBuffer(body)
            )
            val h = req.headers()
            h.set(HttpHeaderNames.AUTHORIZATION, "Bearer tok-${i and 255}-abcdefghijklmnop")
            h.set("X-Geo", GEOS[i and 7])
            h.set("X-Quota", "q${i and 63}")
            h.set("X-Client", "client-${i and 127}")
            h.set("X-Exp", if (i and 3 == 0) "on" else "off")
            h.setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
            return req
        }

        val GEOS = arrayOf("eu-west", "eu-north", "us-east", "us-west", "ap-south", "sa-east", "af", "me")
    }
}

/** One measured run: returns responses served and the wall time it took. */
fun drive(
    seconds: Int, threads: Int, connections: Int, inFlight: Int,
    /** Run over the measured window only, never over the warm-up. */
    duringMeasurement: (() -> Unit)? = null,
): Pair<Long, Double> {
    val server = Server(threads, labelled = false).start()
    val client = Client(server.address, connections, inFlight).start()
    try {
        // Warm up first and throw the count away: HTTP decoding, the route scan and the hash loops
        // are all C2's business, and a cold measurement would be measuring the compiler.
        Thread.sleep(5_000)
        val from = client.responses.get()
        val t0 = System.nanoTime()
        if (duringMeasurement != null) duringMeasurement() else Thread.sleep(seconds * 1000L)
        val elapsed = (System.nanoTime() - t0) / 1e9
        return (client.responses.get() - from) to elapsed
    } finally {
        client.stop()
        server.stop()
    }
}

/**
 * Step 1 of running a trial: **qualify the candidate before instrumenting it.**
 *
 * Records JFR over the measured window and asks what a flame graph could have told you about this
 * pipeline. The question is not whether the workload is slow — it is whether the *handler* costs are
 * recoverable from a stack, because a workload whose answer is already on a flame graph proves
 * nothing about this tool.
 */
fun qualify(seconds: Int, threads: Int, connections: Int, inFlight: Int) {
    val jfr = Files.createTempFile("netty-trial", ".jfr")
    val (served, elapsed) = drive(seconds, threads, connections, inFlight) {
        // The shared helper now writes to disk, which is what makes this count meaningful - see
        // its comment. Before that fix a long run silently discarded its early samples.
        val recording = recordExecutionSamples(1)
        Thread.sleep(seconds * 1000L)
        recording.stop()
        recording.dump(jfr)
        recording.close()
    }
    println(
        String.format(
            Locale.ROOT, "\nserved %,d responses in %.2f s — %,.0f req/s", served, elapsed, served / elapsed
        )
    )
    // Deep enough to reach our own handlers: Netty puts twenty frames of its own above them, so a
    // top-25 table stops before the pipeline the trial is about.
    analyzeJfr(jfr, top = 45, collapsedOut = null)

    // The question this trial exists to answer, asked of the flame graph directly. Every policy is
    // an instance of one class, so a stack can only ever produce one number for the four of them.
    println()
    println("=".repeat(78))
    println("WHAT A FLAME GRAPH CAN NAME HERE")
    println("=".repeat(78))
    println("distinct classes — these it names, and that is the control:")
    println("    AuthHandler, RouteHandler, RenderHandler")
    println("shared class — these it cannot separate, and there are ${POLICIES.size} of them:")
    for ((name, header, depth) in POLICIES) {
        println(String.format(Locale.ROOT, "    %-20s reads %-10s hashes %,5d bytes", name, header, depth))
    }
    println("all four appear as one frame: PolicyHandler.channelRead0")
    Files.deleteIfExists(jfr)
}

fun main(args: Array<String>) {
    val opt = args.filter { it.startsWith("--") }
        .associate { val p = it.removePrefix("--").split("=", limit = 2); p[0] to p.getOrElse(1) { "true" } }
    val seconds = opt["seconds"]?.toInt() ?: 20
    val threads = opt["threads"]?.toInt() ?: 4
    val connections = opt["connections"]?.toInt() ?: 16
    val inFlight = opt["inflight"]?.toInt() ?: 8

    println("=".repeat(96))
    println("TRIAL 3 — Netty: $threads event loops, $connections connections, $inFlight in flight")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, " +
            "cores: ${Runtime.getRuntime().availableProcessors()}")
    println("=".repeat(96))

    if (opt["qualify"] != null) {
        qualify(seconds, threads, connections, inFlight)
        return
    }

    val (served, elapsed) = drive(seconds, threads, connections, inFlight)
    println(
        String.format(
            Locale.ROOT, "\nserved %,d responses in %.2f s — %,.0f req/s, %.1f us per request per loop",
            served, elapsed, served / elapsed, elapsed * 1e6 * threads / served
        )
    )
}
