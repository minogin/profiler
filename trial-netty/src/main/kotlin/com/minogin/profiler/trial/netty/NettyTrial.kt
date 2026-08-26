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
import java.util.concurrent.TimeUnit
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

    /**
     * Awaited, and that is not tidiness.
     *
     * `shutdownGracefully()` returns a future and has a default *quiet period of two seconds*, so
     * without the `sync()` the loops of one A/B arm are still winding down while the next arm is
     * being measured — seven extra threads competing for cores, which produced an A/B claiming that
     * instrumentation made the server 21% faster.
     */
    fun stop() {
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync()
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync()
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

    /** Awaited, for the reason in [Server.stop]. */
    fun stop() {
        running = false
        channels.forEach { it.close().sync() }
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync()
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
    val collapsed = Files.createTempFile("netty-collapsed", ".txt")
    analyzeJfr(jfr, top = 45, collapsedOut = collapsed)
    printNestingCheck(collapsed)

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

    if (opt["ab"] != null) {
        runAb(opt["rounds"]?.toInt() ?: 4, seconds, threads, connections, inFlight)
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

/** The three configurations the A/B compares. See [runAb]. */
private enum class Arm(val label: String, val labels: Boolean, val sampler: Boolean) {
    INERT("inert (branch, no hook)", labels = false, sampler = false),
    HOOK("labels, no sampler", labels = true, sampler = false),
    FULL("labels + sampler", labels = true, sampler = true),
}

/**
 * Step 3 of running a trial: **do not let the instrumentation change the workload.**
 *
 * On Lucene a careless wrapper made the workload 13.3% slower and moved one clause from seventh to
 * third — the numbers were internally consistent and described a program the library would never
 * have run. Nothing in a report can catch that; only a comparison against the uninstrumented
 * workload can.
 *
 * Three arms, because the hook's cost and the sampler thread's cost are different questions and a
 * two-way comparison answers neither. **Interleaved and with the order reversed every other round**,
 * which is not fussiness: this laptop's throughput moved by a factor of two inside a single Lucene
 * comparison, and only interleaving kept that from landing entirely on whichever arm ran last.
 * ABBA rather than ABAB, because a monotonic drift aliases straight onto an alternating order.
 *
 * **What this cannot separate.** The INERT arm still contains the `labelled` branch — a test of a
 * final `-1` field. Removing it needs a second set of handler classes, and the branch is in every
 * arm of every comparison this trial actually makes, so it cancels there. It does mean "inert" is
 * a floor rather than the bare workload.
 */
fun runAb(rounds: Int, seconds: Int, threads: Int, connections: Int, inFlight: Int) {
    val totals = HashMap<Arm, MutableList<Double>>()
    for (a in Arm.entries) totals[a] = ArrayList()

    println("\n" + "=".repeat(96))
    println("A/B AGAINST THE BARE WORKLOAD — $rounds rounds of ${seconds}s per arm, order reversed every other round")
    println("=".repeat(96))

    // One server, one client, one set of connections, alive for the whole comparison. Only
    // [Switch] and the sampler move between arms.
    val server = Server(threads, labelled = true).start()
    val client = Client(server.address, connections, inFlight).start()
    try {
        Thread.sleep(8_000)
        for (round in 0 until rounds) {
            val order = if (round % 2 == 0) Arm.entries.toList() else Arm.entries.reversed()
            for (arm in order) {
                Switch.on = arm.labels
                // A beat for the flipped branch to settle and for the sampler thread to come and
                // go, so neither lands inside the measured window.
                Thread.sleep(700)
                if (arm.sampler) Profiler.start(stepMillis = 1.0, strict = false)
                val from = client.responses.get()
                val t0 = System.nanoTime()
                Thread.sleep(seconds * 1000L)
                val rate = (client.responses.get() - from) / ((System.nanoTime() - t0) / 1e9)
                if (arm.sampler) Profiler.stop()
                totals[arm]!!.add(rate)
                println(String.format(Locale.ROOT, "  round %d  %-26s %,10.0f req/s", round + 1, arm.label, rate))
            }
        }
    } finally {
        client.stop()
        server.stop()
    }

    // Raw means are useless here and the first version of this printed them. This laptop's
    // throughput fell from 164k to 58k req/s across four rounds — a 2.8x collapse — so an arm's
    // average is mostly a statement about when it happened to run. Normalising each measurement by
    // its own round's mean removes any drift that affected the whole round, which is nearly all of
    // it, and leaves the arm-to-arm difference that the comparison is about.
    val ratios = HashMap<Arm, MutableList<Double>>()
    for (a in Arm.entries) ratios[a] = ArrayList()
    for (round in 0 until rounds) {
        val roundMean = Arm.entries.map { totals[it]!![round] }.average()
        for (a in Arm.entries) ratios[a]!!.add(totals[a]!![round] / roundMean)
    }

    val base = ratios[Arm.INERT]!!.average()
    println("\n  %-26s %12s %12s %10s %10s".format("arm", "mean req/s", "vs round", "vs inert", "raw spread"))
    for (arm in Arm.entries) {
        val v = totals[arm]!!
        val r = ratios[arm]!!.average()
        println(
            String.format(
                Locale.ROOT, "  %-26s %,12.0f %11.3f %9.2f%% %9.1f%%",
                arm.label, v.average(), r, (r - base) / base * 100, (v.max() - v.min()) / v.average() * 100
            )
        )
    }

    // Even normalised, the comparison is only worth reading if the effect survives the scatter of
    // the round-by-round ratios. On Lucene the equivalent check said no, and saying so was the
    // finding rather than a failure to produce one.
    fun sd(v: List<Double>): Double {
        val m = v.average()
        return kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1).coerceAtLeast(1))
    }
    val effect = kotlin.math.abs((ratios[Arm.FULL]!!.average() - base) / base * 100)
    // Standard error of the difference between two arm means, in the same percentage units.
    val se = kotlin.math.sqrt(
        sd(ratios[Arm.FULL]!!).let { it * it } / rounds + sd(ratios[Arm.INERT]!!).let { it * it } / rounds
    ) / base * 100
    println(
        String.format(
            Locale.ROOT,
            "\n  full against inert: %.2f%% +/- %.2f%% (1 s.e. over %d rounds) — %s",
            (ratios[Arm.FULL]!!.average() - base) / base * 100, se, rounds,
            if (effect > 2 * se) "readable, the effect is over twice its own error"
            else "INCONCLUSIVE: inside twice its own error, so this run does not separate them"
        )
    )
}

/**
 * Could a flame graph separate the four policies after all, by how deeply they nest?
 *
 * This is the check that could weaken the trial's headline, so it is worth running rather than
 * assuming. Netty's pipeline **nests**: each handler calls `ctx.fireChannelRead`, which invokes the
 * next handler *inside* the current frame. So the fourth policy runs with four `PolicyHandler`
 * frames on the stack and the first with one, and a recursion-aware reading of the collapsed stacks
 * could in principle tell them apart — which would mean the identity is in the stack after all, just
 * not where an ordinary flame graph looks.
 *
 * Counting occurrences per collapsed stack is exactly that reading, done as favourably to the flame
 * graph as it can be done.
 */
private fun printNestingCheck(collapsed: java.nio.file.Path) {
    val byDepth = HashMap<Int, Long>()
    var withPolicy = 0L
    var total = 0L
    Files.newBufferedReader(collapsed).use { r ->
        while (true) {
            val line = r.readLine() ?: break
            val cut = line.lastIndexOf(' ')
            if (cut < 0) continue
            val count = line.substring(cut + 1).trim().toLongOrNull() ?: continue
            total += count
            // How many PolicyHandler frames are on this stack: 1 means the first policy in the
            // chain was running, 4 means the fourth, since each nests inside the last.
            var n = 0
            var i = line.indexOf("PolicyHandler.channelRead0")
            while (i >= 0) {
                n++
                i = line.indexOf("PolicyHandler.channelRead0", i + 1)
            }
            if (n > 0) {
                withPolicy += count
                byDepth.merge(n, count, Long::plus)
            }
        }
    }
    println()
    println("=".repeat(78))
    println("COULD A FLAME GRAPH SEPARATE THE POLICIES BY NESTING DEPTH?")
    println("=".repeat(78))
    println(
        String.format(
            Locale.ROOT, "  %,d of %,d samples have a PolicyHandler frame (%.1f%%)",
            withPolicy, total, withPolicy * 100.0 / total.coerceAtLeast(1)
        )
    )
    println(String.format(Locale.ROOT, "  %-24s %10s %9s", "PolicyHandler frames", "samples", "share"))
    for (d in byDepth.keys.sorted()) {
        val c = byDepth[d]!!
        println(
            String.format(
                Locale.ROOT, "  %-24d %,10d %8.2f%%   (chain position %d)",
                d, c, c * 100.0 / withPolicy.coerceAtLeast(1), d
            )
        )
    }
    Files.deleteIfExists(collapsed)
}
