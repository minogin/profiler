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
import com.minogin.profiler.Profiler
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

/**
 * The label ids, resolved once for the whole server.
 *
 * Netty builds a fresh pipeline per connection, so sixteen connections give sixteen handler objects
 * per stage. All of them must share one id: an id per *object* would split one policy across
 * sixteen labels reading a sixteenth each, and sixteen small rows look like a finding rather than
 * like a mistake.
 */
class Labels(val auth: Int, val route: Int, val render: Int, val policies: IntArray) {
    companion object {
        fun register() = Labels(
            Profiler.register("auth"),
            Profiler.register("route"),
            Profiler.register("render"),
            IntArray(POLICIES.size) { Profiler.register(POLICIES[it].first) },
        )

        /** The unlabelled configuration: same objects, same branch, no hook. */
        fun none() = Labels(-1, -1, -1, IntArray(POLICIES.size) { -1 })
    }
}

class Server(private val threads: Int, private val labelled: Boolean) {
    private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory())

    /** The labels, or all -1 when this server runs unlabelled. See [Labels]. */
    private val ids = if (labelled) Labels.register() else Labels.none()

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
                    p.addLast(AuthHandler(ids.auth))
                    p.addLast(RouteHandler(ids.route))
                    for ((i, policy) in POLICIES.withIndex()) {
                        p.addLast(PolicyHandler(policy.first, policy.second, policy.third, ids.policies[i]))
                    }
                    p.addLast(RenderHandler(ids.render))
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

    if (opt["labels"] != null) {
        profile(seconds, threads, connections, inFlight)
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

/**
 * The trial proper: labels on, sampler on, and the report the tool would give a user.
 *
 * `strict = false` deliberately. Two of these labels are plausibly under the 50 ns floor — the route
 * scan and the cheapest policy — and the floor check is fatal by default. On code you own that is
 * the right default; here it would stop the run before the interesting number was collected, which
 * is exactly the situation the switch exists for.
 */
fun profile(seconds: Int, threads: Int, connections: Int, inFlight: Int) {
    val server = Server(threads, labelled = true).start()
    val client = Client(server.address, connections, inFlight).start()
    try {
        Thread.sleep(5_000)
        val from = client.responses.get()
        Profiler.start(stepMillis = 1.0, strict = false)
        val t0 = System.nanoTime()
        Thread.sleep(seconds * 1000L)
        val elapsed = (System.nanoTime() - t0) / 1e9
        val served = client.responses.get() - from
        val report = Profiler.stop()
        println(
            String.format(
                Locale.ROOT, "\nserved %,d responses in %.2f s — %,.0f req/s (%.2f us of loop time each)",
                served, elapsed, served / elapsed, elapsed * 1e6 * threads / served
            )
        )
        println(report.render())
        printPolicyFit(report)
        // The client's own event loops are in this JVM and carry no labels, so they land in the
        // unlabelled column and drag coverage down. Said out loud rather than left for the reader
        // to wonder about, since "half the run is outside every label" is otherwise a finding.
        println(
            "note: $threads server event loops are labelled; the client's 2 loops and Netty's own " +
                    "codec work are not, and everything unlabelled is in the coverage line above"
        )
    } finally {
        client.stop()
        server.stop()
    }
}

/**
 * The third truth: the configuration should predict the measurement.
 *
 * Each policy is configured to hash a known number of bytes, so its cost per call ought to be a
 * straight line — a fixed cost for the header lookup and the call, plus a rate per byte. Nothing in
 * the profiler knows that, so if the labels' implied durations fall on that line then they are
 * measuring the thing they claim to and not an artifact of where the labels sit.
 *
 * The effective byte count is not the configured one. Bodies here run from 128 to 2,048 bytes and a
 * policy hashes `min(depth, body)`, so the 1,024-byte policy is capped by the shorter half of them
 * and really averages 800. Using the configured 1,024 would make the fit look worse than it is and
 * would be our arithmetic error, not the tool's.
 */
private fun printPolicyFit(report: com.minogin.profiler.Report) {
    val bodies = IntArray(16) { 128 + it * 128 }
    fun effective(depth: Int) = bodies.sumOf { minOf(depth, it) }.toDouble() / bodies.size

    val points = POLICIES.mapNotNull { (name, _, depth) ->
        val op = report.operations.firstOrNull { it.name == name && it.calls > 0 } ?: return@mapNotNull null
        Triple(name, effective(depth), report.occupancyNanosOf(op) / op.calls)
    }
    if (points.size < 3) return

    // Ordinary least squares on four points. Two parameters, four measurements, so it can fail.
    val n = points.size
    val mx = points.sumOf { it.second } / n
    val my = points.sumOf { it.third } / n
    val sxy = points.sumOf { (it.second - mx) * (it.third - my) }
    val sxx = points.sumOf { (it.second - mx) * (it.second - mx) }
    val slope = sxy / sxx
    val intercept = my - slope * mx

    println("\n" + "=".repeat(78))
    println("CONFIGURATION AGAINST MEASUREMENT — do the four policies fall on a line?")
    println("=".repeat(78))
    println(String.format(Locale.ROOT, "  fitted: %.1f ns fixed + %.3f ns per byte hashed", intercept, slope))
    println(
        String.format(
            Locale.ROOT, "  %-20s %8s %12s %12s %8s %8s %7s",
            "policy", "bytes", "measured", "predicted", "error", "noise", "in x noise"
        )
    )
    // The residual has to be read against each label's own noise floor, and the two are far apart
    // here: the largest policy has fifteen times the hits of the smallest. A fit with one point at
    // 800 bytes and three under 250 is also pinned by that point, so whatever error is left lands on
    // the small ones. Quoting a single "worst residual" hides both effects.
    var worstSigma = 0.0
    var worstBig = 0.0
    for ((name, bytes, measured) in points) {
        val op = report.operations.first { it.name == name }
        val noise = report.noiseFloorOf(op) * 100
        val predicted = intercept + slope * bytes
        val err = (measured - predicted) / measured * 100
        val sigma = if (noise > 0) kotlin.math.abs(err) / noise else Double.NaN
        if (sigma > worstSigma) worstSigma = sigma
        // "Big" means holding enough of the run to act on. By the accuracy principle a miss on a
        // label holding 4% cannot change anybody's next move; a miss on one holding 62% can.
        if (report.shareOf(op) > 0.10 && kotlin.math.abs(err) > kotlin.math.abs(worstBig)) worstBig = err
        println(
            String.format(
                Locale.ROOT, "  %-20s %8.0f %9.1f ns %9.1f ns %7.1f%% %7.2f%% %6.1fx",
                name, bytes, measured, predicted, err, noise, sigma
            )
        )
    }
    println(
        String.format(
            Locale.ROOT,
            "  worst residual on a policy holding over 10%% of the run: %.1f%%; worst anywhere: %.1f x its own noise",
            worstBig, worstSigma
        )
    )
}
