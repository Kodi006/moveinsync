package com.moveinsync.roster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

@Service
public class EmployeeReadService {

    private final EmployeeRepository employees;
    private final Cache<EmployeeKey, Optional<Employee>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(100_000)
            .build();

    public EmployeeReadService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public Optional<Employee> findById(String tenantId, String employeeId) {
        EmployeeKey key = new EmployeeKey(tenantId, employeeId);
        return cache.get(key, ignored -> employees.findById(tenantId, employeeId));
    }

    /** Evict only after commit so a rollback leaves the valid cached value intact. */
    public void evictAfterCommit(String tenantId, String employeeId) {
        EmployeeKey key = new EmployeeKey(tenantId, employeeId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(key);
                }
            });
        } else {
            cache.invalidate(key);
        }
    }

    private record EmployeeKey(String tenantId, String employeeId) { }
}
