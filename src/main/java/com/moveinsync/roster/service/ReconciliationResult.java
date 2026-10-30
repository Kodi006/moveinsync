package com.moveinsync.roster.service;

public record ReconciliationResult(long runId, String status, int totalRows,
                                   int newCount, int changedCount, int unchangedCount,
                                   int invalidCount, int missingCount,
                                   int activeInStore, double missingPercentage) {
}
