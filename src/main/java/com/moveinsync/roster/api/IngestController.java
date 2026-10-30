package com.moveinsync.roster.api;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.RosterIngestService;
import com.moveinsync.roster.service.IngestMetrics;
import com.moveinsync.roster.validation.RecordValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/roster/events")
public class IngestController {

    private final RosterIngestService ingest;
    private final RecordValidator validator;
    private final IngestMetrics metrics;

    public IngestController(RosterIngestService ingest, RecordValidator validator, IngestMetrics metrics) {
        this.ingest = ingest;
        this.validator = validator;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<IngestBatchResponse> ingest(@RequestBody IngestBatchRequest batch) {
        List<RecordIngestResult> results = new ArrayList<>();
        if (batch == null || batch.events() == null) {
            return ResponseEntity.ok(new IngestBatchResponse(List.of()));
        }
        for (RosterEventRequest request : batch.events()) {
            results.add(processOne(request));
        }
        return ResponseEntity.status(HttpStatus.OK).body(new IngestBatchResponse(results));
    }

    private RecordIngestResult processOne(RosterEventRequest request) {
        if (request == null) {
            return new RecordIngestResult(null, "REJECTED", "MISSING_REQUIRED_FIELD");
        }
        RecordValidator.ValidationResult validation = validator.validate(
                request.tenantId(), request.employeeId(), request.owner(), request.version(),
                request.eventType(), request.payload());
        if (!validation.valid()) {
            metrics.rejected(TenantContext.current().tenantId(), request.owner());
            return new RecordIngestResult(request.employeeId(), "REJECTED", validation.reason());
        }
        if (!TenantContext.current().tenantId().equals(request.tenantId())) {
            metrics.rejected(TenantContext.current().tenantId(), request.owner());
            return new RecordIngestResult(request.employeeId(), "REJECTED", "TENANT_MISMATCH");
        }
        try {
            RosterEvent event = request.toDomainEvent();
            Decision decision = ingest.ingest(event);
            return new RecordIngestResult(request.employeeId(), decision.name(), null);
        } catch (RuntimeException failure) {
            // Keep one record's processing failure from aborting the rest of the batch.
            metrics.rejected(TenantContext.current().tenantId(), request.owner());
            return new RecordIngestResult(request.employeeId(), "REJECTED", "PROCESSING_ERROR");
        }
    }
}
