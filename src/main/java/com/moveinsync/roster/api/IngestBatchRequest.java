package com.moveinsync.roster.api;

import java.util.List;

public record IngestBatchRequest(List<RosterEventRequest> events) {
}
