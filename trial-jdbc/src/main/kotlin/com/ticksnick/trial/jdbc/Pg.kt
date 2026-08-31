package com.ticksnick.trial.jdbc

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The database the trial talks to: PostgreSQL in a container, on a port of its own.
 *
 * **Driven by `docker run` rather than by a library.** Testcontainers would do this too, and would
 * bring its own threads into the very process being profiled — which is the one thing this trial
 * cannot afford, since every share in the report is taken over the threads the sampler can see.
 * Forty lines of `ProcessBuilder` keep the profiled JVM containing nothing but the client.
 *
 * **A real engine over a real socket, and that is the whole point of the fourth trial.** Calcite is
 * single-threaded and pure CPU, Lucene's index is page-cached, and Netty's loopback request never
 * blocks — so `mean - busy/exec`, the quantity the coarse tier exists to produce, has never met a
 * foreign workload whose answer was anything but zero. Here a thread is genuinely stopped, waiting
 * for a machine that is not this program, and the question is whether the report says so.
 */
object Pg {
    const val IMAGE = "postgres:17-alpine"
    const val NAME = "profiler-trial-pg"

    /** Not 5432: a developer machine often already has a PostgreSQL, and silently profiling theirs
     *  instead of ours would be a very confusing afternoon. */
    const val PORT = 55432

    const val DB = "trial"
    const val USER = "trial"
    const val PASSWORD = "trial"

    val url: String get() = "jdbc:postgresql://localhost:$PORT/$DB"

    fun properties(): Properties = Properties().apply {
        setProperty("user", USER)
        setProperty("password", PASSWORD)
    }

    private fun docker(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out.trim()
    }

    fun daemonRunning(): Boolean = docker("info", "--format", "{{.ServerVersion}}").first == 0

    private fun containerState(): String? {
        val (code, out) = docker("inspect", "-f", "{{.State.Status}}", NAME)
        return if (code == 0) out else null
    }

    /**
     * Brings the container up, reusing one that is already there.
     *
     * Reuse is deliberate and not an optimisation: the generated table takes minutes to build, and a
     * trial that threw its data away every run would be measured against a cold cache one time in
     * one. `--fresh` is how you ask for the other thing.
     */
    fun start(): Boolean {
        when (containerState()) {
            "running" -> {
                println("container $NAME already running on port $PORT")
                return awaitReady()
            }

            "exited", "created" -> {
                println("starting existing container $NAME")
                val (code, out) = docker("start", NAME)
                if (code != 0) {
                    println("  docker start failed: $out")
                    return false
                }
                return awaitReady()
            }
        }
        println("creating container $NAME from $IMAGE on port $PORT")
        val (code, out) = docker(
            "run", "-d", "--name", NAME,
            "-e", "POSTGRES_DB=$DB", "-e", "POSTGRES_USER=$USER", "-e", "POSTGRES_PASSWORD=$PASSWORD",
            // Shared buffers large enough that the working set lives in the server rather than being
            // re-read every query. The wait being measured is a round trip to another process, not a
            // disk seek — those are different waits and mixing them would muddle the finding.
            "-p", "$PORT:5432", IMAGE, "-c", "shared_buffers=512MB", "-c", "max_connections=64",
        )
        if (code != 0) {
            println("  docker run failed: $out")
            return false
        }
        return awaitReady()
    }

    fun remove() {
        docker("rm", "-f", NAME)
        println("removed container $NAME")
    }

    /** Polls until the server accepts a connection. First start includes initdb, so this is slow. */
    private fun awaitReady(seconds: Int = 90): Boolean {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var lastError: String? = null
        while (System.nanoTime() < deadline) {
            try {
                DriverManager.getConnection(url, properties()).use { it.createStatement().use { s -> s.execute("select 1") } }
                return true
            } catch (e: Exception) {
                lastError = e.message
                Thread.sleep(250)
            }
        }
        println("  database did not become ready in ${seconds}s: $lastError")
        return false
    }

    fun connect(): Connection = DriverManager.getConnection(url, properties())
}

/**
 * How many rows the table holds. Large enough that a query is real work for the server and small
 * enough to generate in about a minute.
 *
 * The point is not to make the *database* slow. It is to make the client wait for something, and to
 * have enough distinct keys that consecutive requests do not simply re-read one cached page.
 */
const val ROWS = 5_000_000

/** Distinct users, so a point query returns a handful of rows out of five million. */
const val USERS = 200_000

/**
 * Creates the table and fills it, once.
 *
 * Generated server-side with `generate_series` rather than by batched inserts from Kotlin: five
 * million round trips would take longer than the rest of this trial put together, and none of it
 * would be measuring anything.
 */
fun ensureSchema(): Boolean {
    Pg.connect().use { c ->
        c.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id         bigint PRIMARY KEY,
                    user_id    int NOT NULL,
                    kind       smallint NOT NULL,
                    amount     numeric(12,2) NOT NULL,
                    created_at timestamptz NOT NULL,
                    payload    text NOT NULL
                )
                """.trimIndent()
            )
        }
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM events").use { rs -> rs.next(); rs.getLong(1) }
        }
        if (count >= ROWS) {
            println("events: $count rows already present")
            return true
        }
        println("generating $ROWS rows (once; this takes a minute or two)")
        val t0 = System.nanoTime()
        c.createStatement().use { s ->
            s.execute("TRUNCATE events")
            s.execute(
                """
                INSERT INTO events (id, user_id, kind, amount, created_at, payload)
                SELECT g,
                       -- g::bigint before the multiply, not after: generate_series yields int, and
                       -- 5,000,000 * 2654435761 overflows int long before any cast on the result
                       -- could rescue it. PostgreSQL says "integer out of range" and it is right.
                       ((g::bigint * 2654435761) % $USERS)::int,
                       (g % 8)::smallint,
                       ((g::bigint * 7919) % 100000) / 100.0,
                       now() - ((g % 2592000) * interval '1 second'),
                       md5(g::text) || md5((g + 1)::text)
                FROM generate_series(1, $ROWS) g
                """.trimIndent()
            )
            // The index is what makes a point query a lookup rather than a scan of five million
            // rows. Without it every request would be seconds long and the trial would be measuring
            // a missing index.
            s.execute("CREATE INDEX IF NOT EXISTS events_user_created ON events (user_id, created_at DESC)")
            s.execute("ANALYZE events")
        }
        println("  generated in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
    }
    return true
}
