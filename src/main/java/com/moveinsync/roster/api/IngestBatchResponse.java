package com.moveinsync.roster.api;

import java.util.List;

public record IngestBatchResponse(List<RecordIngestResult> results) {
}
