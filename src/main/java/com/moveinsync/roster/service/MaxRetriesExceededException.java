package com.moveinsync.roster.service;

/**
 * Thrown when the CAS retry budget is exhausted without making progress.
 * This indicates abnormally high write contention on a single employee record —
 * almost certainly a bug in the caller (sending the same record from many threads)
 * or a misconfigured fanout.
 *
 * <p>The message carries only the employee ID — <strong>never name, email, or any
 * other PII</strong> — because exception messages frequently end up in logs and
 * error responses.
 */
public class MaxRetriesExceededException extends RuntimeException {

    public MaxRetriesExceededException(String tenantId, String employeeId) {
        // String.formatted is Java 15+: equivalent to String.format but as an instance method.
        super("CAS retry budget exhausted for employee %s in tenant %s"
                .formatted(employeeId, tenantId));
    }
}
