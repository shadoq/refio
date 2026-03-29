package pl.jclab.refio.services.session

import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.components.toolbar.StatusBar

class StatusBarIntegration {

    private val logger = dualLogger("StatusBarIntegration")
    private var statusBar: StatusBar? = null

    fun setStatusBar(statusBar: StatusBar) {
        this.statusBar = statusBar
        logger.info { "StatusBar reference set" }
    }

    fun getStatusBar(): StatusBar? = statusBar
}
