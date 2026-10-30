package com.moveinsync.roster.api;

import com.moveinsync.roster.service.ReconciliationApprovalService;
import com.moveinsync.roster.security.Role;
import com.moveinsync.roster.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationApprovalService approvals;

    public ReconciliationController(ReconciliationApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ReconciliationApprovalService.ReconciliationRunView> listRuns() {
        return approvals.listRuns(TenantContext.current().tenantId());
    }

    @PostMapping("/{id}/approve")
    public ReconciliationApprovalService.ApprovalResult approve(
            @PathVariable long id, @RequestBody ApprovalRequest request) {
        // Operator identity is request data here; role and tenant come only from the API key.
        if (TenantContext.current().role() != Role.OPERATOR) {
            throw new ForbiddenOperationException();
        }
        return approvals.approve(TenantContext.current().tenantId(), id, request.operatorId());
    }

    @ExceptionHandler(ReconciliationApprovalService.RunNotApprovableException.class)
    public ResponseEntity<Map<String, String>> cannotApprove() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "RUN_NOT_APPROVABLE"));
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "FORBIDDEN"));
    }

    private static class ForbiddenOperationException extends RuntimeException { }
}
