package com.minogin.profiler.trial.jdbc

import com.minogin.profiler.Profiler
import com.minogin.profiler.op
import com.minogin.profiler.propagating
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * The fourth trial: a database over a socket, which is the first workload here with any waiting in
 * it.
 *
 * Every trial so far has been CPU. Calcite plans on one thread; Lucene's index is page-cached and
 * its clauses read `waiting 0.0%`; Netty's request is loopback and pure compute. So the coarse
 * tier's headline claim — *"`mean - busy/exec` is the waiting, which is the one thing a fine label
 * can never tell you"* — has never been checked against foreign code whose answer was not zero. The
 * only waiting with a known truth anywhere in this project is our own contended lock in the bench.
 *
 * **And there is a specific thing to find out.** `working` became a printed column in phase 5b, and
 * it is built on `Thread.getState`, which [findings.md] already records as blind to native waiting:
 * a thread stopped inside a socket read is `RUNNABLE` as far as Java is concerned. If that blindness
 * bites, `working` will count blocked threads as working and `waiting` will under-report — on
 * exactly the workload where the distinction is the reason anyone opened the report. This trial is
 * built to answer that with a number.
 *
 * **The truth is the operating system's.** [ThreadMXBean.getThreadCpuTime] says what a thread
 * actually burned, and it sees through the JNI boundary that thread state cannot. So the check is:
 *
 * ```
 * working x mean span x executions      what the profiler says the requests spent on a CPU
 * CPU actually used by those threads    what the OS says they spent
 * ```
 *
 * They should agree. If the first is far larger, the column is counting threads that were stopped.
 */

/**
 * The labels, registered where they are declared.
 *
 * A handle can only come from registration now, so there is no sentinel to initialise them to and no
 * reason to want one — which is the API telling you something true: an operation that has not been
 * registered is not an operation.
 */
private val REQUEST = Profiler.registerCoarse("request")

/**
 * Borrowing a connection. It blocks when the pool is exhausted, which is a wait of a completely
 * different kind from the socket wait below — one is this program queueing on itself, the other is
 * the database taking its time. Separating them is the sort of thing a share is for.
 */
private val ACQUIRE = Profiler.registerFine("acquire")

/** The round trip. Nearly all of a request's wall time, and nearly none of its CPU. */
private val EXECUTE = Profiler.registerFine("execute")

/** Walking the result set. Partly local decoding, partly more socket reads for further rows. */
private val FETCH = Profiler.registerFine("fetch")

private val MERGE = Profiler.registerFine("merge")

/**
 * One query in the mix, and the mix exists so the spans have a distribution.
 *
 * A percentile over a set of identical requests describes nothing. Two point lookups against one
 * aggregate give p50, p90 and p99 that are genuinely different numbers, in the same way the bench's
 * variable request length does.
 */
private class Query(val name: String, val sql: String, val heavy: Boolean)

private val QUERIES = listOf(
    Query(
        "recent-by-user",
        "SELECT id, kind, amount, created_at FROM events WHERE user_id = ? ORDER BY created_at DESC LIMIT 20",
        heavy = false,
    ),
    Query(
        "sum-by-user",
        "SELECT kind, count(*), sum(amount) FROM events WHERE user_id = ? GROUP BY kind",
        heavy = false,
    ),
    Query(
        // Deliberately the expensive one: a range over thousands of rows, so the server takes real
        // time and the client is stopped for a while rather than for a round trip.
        "window-scan",
        "SELECT count(*), sum(amount) FROM events WHERE user_id BETWEEN ? AND ? + 400",
        heavy = true,
    ),
)

/** Result of one query, kept so nothing can be optimised away. */
object Sink {
    @Volatile
    var last: Any? = null
}

/**
 * One request: [fanout] queries issued in parallel across the pool, then merged.
 *
 * The shape is Lucene's — a caller that opens a span, hands work to a pool and waits — and that is
 * the point of running it again: there the helpers computed, here they are stopped on a socket. The
 * two should look completely different in the `inside` and `working` columns, and if they do not,
 * one of those columns is not measuring what it says.
 */
private class Bed(
    val threads: Int,
    val fanout: Int,
    propagate: Boolean,
) : AutoCloseable {

    val pool: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = Pg.url
        username = Pg.USER
        password = Pg.PASSWORD
        // One connection per worker: the queries should wait for the database and not for each
        // other. A pool smaller than the fan-out would make `acquire` the finding, which is a real
        // thing to measure but a different one.
        maximumPoolSize = threads
        minimumIdle = threads
        poolName = "trial-pool"
    })

    val workers: ExecutorService =
        Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "jdbc-worker").also { it.isDaemon = true }
        }

    /**
     * What the queries are actually submitted to.
     *
     * The whole of propagation, and behind a switch so the before and the after come out of one
     * binary — the same discipline the Lucene trial uses. Without it the request's context stays on
     * the calling thread, every query runs under no span at all, and the report says the request
     * spent its life waiting.
     */
    val submitTo: ExecutorService = if (propagate) workers.propagating() else workers

    private var seed = 12345L

    private fun nextKey(): Int {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 33).toInt() and 0x7fffffff) % USERS
    }

    fun request(): Int {
        val tasks = (0 until fanout).map {
            val q = QUERIES[nextKey() % QUERIES.size]
            val key = nextKey()
            Callable { runQuery(q, key) }
        }
        val rows = submitTo.invokeAll(tasks).sumOf { it.get() }
        return op(MERGE) {
            // Nothing clever: the merge exists so that the request has some work of its own and the
            // cross-tabulation is not a single row.
            var h = 0
            for (i in 0 until rows) h = h * 31 + i
            Sink.last = h
            rows
        }
    }

    private fun runQuery(q: Query, key: Int): Int {
        val c = op(ACQUIRE) { pool.connection }
        try {
            c.prepareStatement(q.sql).use { ps ->
                ps.setInt(1, key)
                if (q.heavy) ps.setInt(2, key)
                val rs = op(EXECUTE) { ps.executeQuery() }
                return op(FETCH) {
                    var n = 0
                    rs.use { while (it.next()) n++ }
                    n
                }
            }
        } finally {
            c.close()
        }
    }

    override fun close() {
        workers.shutdown()
        pool.close()
    }
}

/**
 * CPU actually burned by the threads that ran the requests, from the operating system.
 *
 * The truth this trial exists to hold `working` against, and the reason it can: `getThreadCpuTime`
 * counts time on a processor, so a thread stopped in a socket read contributes nothing to it — where
 * `Thread.getState` reports that same thread as `RUNNABLE` and cannot do otherwise.
 *
 * Summed over every live thread rather than a remembered set: the pool creates its threads lazily
 * and HikariCP has housekeeping threads of its own, and a truth that missed either would be a
 * comparison against part of the process.
 */
private fun processCpuNanos(): Long {
    val mx = ManagementFactory.getThreadMXBean()
    var total = 0L
    for (id in mx.allThreadIds) {
        val t = mx.getThreadCpuTime(id)
        if (t > 0) total += t
    }
    return total
}

private fun millis(nanos: Double) = nanos / 1e6

private fun load(seconds: Int, threads: Int, fanout: Int, propagate: Boolean, step: Double, sampler: Boolean) {
    Bed(threads, fanout, propagate).use { bed ->
        println(
            "threads=$threads fanout=$fanout propagation=${if (propagate) "ON" else "OFF"}" +
                    "; warm-up, then $seconds s of requests"
        )
        // Warm-up matters more here than in a pure-CPU trial: the first use of a connection also
        // pays for the TCP handshake, the authentication round trip and the first prepare of every
        // statement, and none of that is what the run is about.
        repeat(200) { bed.request() }

        val cpuBefore = processCpuNanos()
        if (sampler) Profiler.start(stepMillis = step)
        val started = System.nanoTime()
        val deadline = started + seconds * 1_000_000_000L

        var requests = 0L
        val spanTotal = AtomicLong()
        while (System.nanoTime() < deadline) {
            val t0 = System.nanoTime()
            op(REQUEST) { bed.request() }
            spanTotal.addAndGet(System.nanoTime() - t0)
            requests++
        }
        val wall = System.nanoTime() - started
        val report = if (sampler) Profiler.stop() else null
        val cpuUsed = processCpuNanos() - cpuBefore

        println(
            String.format(
                Locale.ROOT, "%,d requests in %.1f s — %.2f ms each, %.1f requests/s",
                requests, wall / 1e9, millis(spanTotal.get().toDouble() / requests), requests / (wall / 1e9)
            )
        )

        if (report != null) {
            println("\n" + report.render())
            checkAgainstTheOperatingSystem(report, requests, wall, cpuUsed, threads)
        }
        println("\n(sink: ${Sink.last})")
    }
}

/**
 * The check the whole trial is for: what the profiler says the requests spent on a CPU, against what
 * the operating system says the process spent.
 *
 * Reported as a ratio and never gated. Two honest reasons the two cannot be identical: the profiler
 * counts only threads inside a labelled span while the OS counts every thread in the process — the
 * pool's housekeeping, the JIT, the sampler itself — and the sampler covers slightly less than the
 * whole run. Both make the OS figure the *larger* one. So the direction that means something is the
 * other one: a profiler figure above the OS total is thread-time the machine never spent, and the
 * only way to get it is by counting threads that were stopped.
 */
private fun checkAgainstTheOperatingSystem(
    report: com.minogin.profiler.Report,
    requests: Long,
    wallNanos: Long,
    cpuNanos: Long,
    threads: Int,
) {
    val c = report.coarse.firstOrNull { it.name == "request" } ?: return
    val busyPerExec = report.busyPerExecutionNanos(c)
    val profilerCpu = busyPerExec * c.count

    println("=".repeat(96))
    println("WAITING, AGAINST THE OPERATING SYSTEM")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "  %-42s %10.2f s", "wall clock of the measured run", wallNanos / 1e9
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-42s %10.2f s   (%d worker threads x the run)",
            "thread-time available to the workers", threads * wallNanos / 1e9, threads
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-42s %10.2f s   <- the truth, and it sees native waits",
            "CPU the process actually used", cpuNanos / 1e9
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-42s %10.2f s   (working %.2f x mean span x %,d executions)",
            "CPU the profiler attributes to requests", profilerCpu / 1e9, c.working, c.count
        )
    )
    val ratio = if (cpuNanos <= 0L) Double.NaN else profilerCpu / cpuNanos
    println(
        String.format(
            Locale.ROOT, "%n  the profiler's figure is %.2fx the operating system's", ratio
        )
    )
    println(
        when {
            ratio.isNaN() -> "  no CPU accounting available on this JVM"
            ratio > 1.15 ->
                "  ABOVE 1: the report is crediting time to a CPU that the machine never spent, which\n" +
                        "  can only be threads that were stopped. `working` is counting blocked threads as\n" +
                        "  working, and `waiting` is under-reporting by the same amount. Java thread state\n" +
                        "  cannot see through a native call and a socket read is a native call."

            ratio < 0.6 ->
                "  WELL BELOW 1: most of the process's CPU is going somewhere the request's span does not\n" +
                        "  cover. Worth knowing which, but it is not the blindness this trial was built for."

            else ->
                "  About 1, allowing for threads the span does not cover: `working` is tracking real CPU,\n" +
                        "  so the waiting the report prints is waiting the machine agrees happened."
        }
    )
    println(
        String.format(
            Locale.ROOT,
            "%n  for reference: inside %.2f, working %.2f, waiting %.1f%%, mean span %.2f ms over %,d requests",
            c.inside, c.working,
            if (c.inclusiveHits == 0L) 0.0 else c.waitingHits * 100.0 / c.inclusiveHits,
            millis(c.meanSpanNanos), requests
        )
    )
}

fun main(args: Array<String>) {
    val opt = args.filter { it.startsWith("--") }
        .associate { val p = it.removePrefix("--").split("=", limit = 2); p[0] to p.getOrElse(1) { "true" } }

    if (opt["down"] != null) {
        Pg.remove()
        return
    }

    println("=".repeat(96))
    println("TRIAL 4 — POSTGRESQL OVER A SOCKET: the first workload here with waiting in it")
    println("=".repeat(96))

    if (!Pg.daemonRunning()) {
        println("Docker is not running. Start Docker Desktop and try again.")
        exitProcess(1)
    }
    if (opt["fresh"] != null) Pg.remove()
    if (!Pg.start()) exitProcess(1)
    if (!ensureSchema()) exitProcess(1)

    load(
        seconds = opt["seconds"]?.toInt() ?: 20,
        threads = opt["threads"]?.toInt() ?: 8,
        fanout = opt["fanout"]?.toInt() ?: 8,
        propagate = opt["propagate"] != "off",
        step = opt["step"]?.toDouble() ?: 1.0,
        sampler = opt["sampler"] != "off",
    )
}
