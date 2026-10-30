package com.moveinsync.roster.api;

import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.EmployeeReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeReadService employees;

    public EmployeeController(EmployeeReadService employees) {
        this.employees = employees;
    }

    @GetMapping("/{employeeId}")
    public ResponseEntity<EmployeeResponse> findById(@PathVariable String employeeId) {
        String tenantId = TenantContext.current().tenantId();
        return employees.findById(tenantId, employeeId)
                .map(EmployeeResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
