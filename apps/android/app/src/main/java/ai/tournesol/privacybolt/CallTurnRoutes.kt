package ai.tournesol.privacybolt

/** Each ICE server keeps its own loopback port for this call's entire lifetime. */
internal class CallTurnRoutes(
    private val firstPort: Int,
    private val limit: Int,
    private val connect: (Int, String) -> Unit,
    private val disconnect: (Int) -> Unit,
) {
    private val routes = linkedMapOf<String, Int>()
    private var closed = false

    @Synchronized
    fun portFor(onion: String): Int {
        check(!closed) { "Call connection closed" }
        require(Regex("[a-z2-7]{56}\\.onion").matches(onion)) { "Invalid call relay" }
        routes[onion]?.let { return it }
        check(routes.size < limit) { "Too many call relays" }
        val port = firstPort + routes.size
        require(port in 1024..65535) { "Invalid call relay port" }
        connect(port, onion) // Only publish the route after its listener starts.
        routes[onion] = port
        return port
    }

    @Synchronized
    fun close() {
        closed = true
        routes.values.forEach { runCatching { disconnect(it) } }
        routes.clear()
    }
}
