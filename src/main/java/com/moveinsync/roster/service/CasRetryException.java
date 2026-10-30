package com.moveinsync.roster.service;

/**
 * Internal control-flow signal: a CAS attempt failed because another writer committed
 * between our read and our write.  The retry loop in {@link RosterIngestService} catches
 * this and re-reads the row before trying again.
 *
 * <p>Stack-trace capture is suppressed ({@code writableStackTrace = false}) because this
 * exception is a loop-control mechanism, not a genuine failure — capturing a stack trace
 * allocates memory and calls into the JVM, which is expensive in a tight retry loop.
 *
 * <p>In C++ you would return a bool or use a discriminated union; Java has no multi-return,
 * so a lightweight exception is the idiomatic alternative here.
 *
 * <p>Package-private: nothing outside the {@code service} package should catch this.
 */
class CasRetryException extends RuntimeException {

    CasRetryException() {
        // super(message, cause, enableSuppression, writableStackTrace)
        super(null, null, true, false);
    }
}
