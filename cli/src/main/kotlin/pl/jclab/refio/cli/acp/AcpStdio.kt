package pl.jclab.refio.cli.acp

import java.io.PrintStream

object AcpStdio {
    /**
     * Takes the process stdout for the protocol and points System.out at stderr.
     *
     * stdout must carry protocol messages and nothing else - one stray line breaks the IDE's
     * parser. After this call a println anywhere in core or in a library lands on stderr, which
     * the IDE only logs. Call it before anything else in ACP mode.
     */
    fun claimStdout(): PrintStream {
        val protocolOut = System.out
        System.setOut(System.err)
        return protocolOut
    }
}
