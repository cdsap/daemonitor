package io.github.cdsap.daemonitor.application.platform

/** Opens a URL in the user's preferred browser or handler. */
fun interface UrlOpener {
    fun open(url: String)
}
