package com.moveinsync.roster.api;

import com.moveinsync.roster.domain.Employee;

public record EmployeeResponse(String employeeId, String name, String email, String managerId,
                               String officeCode, String status, String pickupPoint,
                               String specialAssistance, int hrVersion, int transportVersion) {
    public static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(employee.employeeId(), employee.name(), employee.email(),
                employee.managerId(), employee.officeCode(), employee.status().name(),
                employee.pickupPoint(), employee.specialAssistance(), employee.hrVersion(),
                employee.transportVersion());
    }
}
