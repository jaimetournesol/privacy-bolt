package ai.tournesol.privacybolt

/** Stable authentication/SFU endpoints for each remote call focus. Publish neither
 * a route nor its URL rewrites until both listeners have started successfully. */
internal class CallFocusRoutes(
    private val firstAuthPort: Int,
    private val firstMediaPort: Int,
    private val limit: Int,
    private val connectAuth: (Int, String) -> Unit,
    private val connectMedia: (Int, String) -> Unit,
    private val disconnect: (Int) -> Unit,
) {
    data class Route(val authPort: Int, val mediaPort: Int)
    private val routes = linkedMapOf<String, Route>()
    private var closed = false

    @Synchronized fun routeFor(onion: String): Route {
        check(!closed) { "Call connection closed" }
        require(Regex("[a-z2-7]{56}\\.onion").matches(onion)) { "Invalid call focus" }
        routes[onion]?.let { return it }
        check(routes.size < limit) { "Too many call focuses" }
        val route = Route(firstAuthPort + routes.size, firstMediaPort + routes.size)
        require(route.authPort in 1024..65535 && route.mediaPort in 1024..65535 && route.authPort != route.mediaPort)
        connectAuth(route.authPort, onion)
        try { connectMedia(route.mediaPort, onion) }
        catch (failure: Exception) {
            runCatching { disconnect(route.authPort) }
            runCatching { disconnect(route.mediaPort) }
            throw failure
        }
        routes[onion] = route
        return route
    }

    @Synchronized fun close() {
        closed = true
        routes.values.forEach {
            runCatching { disconnect(it.authPort) }
            runCatching { disconnect(it.mediaPort) }
        }
        routes.clear()
    }
}
