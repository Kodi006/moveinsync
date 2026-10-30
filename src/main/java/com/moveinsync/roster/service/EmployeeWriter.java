package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EmployeeWriter {

    private final EmployeeRepository repo;
    private final OutboxService outbox;

    public EmployeeWriter(EmployeeRepository repo, OutboxService outbox) {
        this.repo = repo;
        this.outbox = outbox;
    }

    /** Public method on a separate bean ensures the Spring transaction proxy is crossed. */
    @Transactional
    public void persist(Employee current, Employee newState, RosterEvent event) {
        if (current == null) {
            try {
                repo.insert(newState);
            } catch (DuplicateKeyException e) {
                throw new CasRetryException();
            }
        } else if (!repo.casUpdate(current, newState)) {
            throw new CasRetryException();
        }
        outbox.enqueue(current, newState, event);
    }
}
