package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class IngestMetrics {

    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void received(RosterEvent event) {
        increment("roster.events.received", event);
    }

    public void decision(Decision decision, RosterEvent event) {
        String metric = switch (decision) {
            case APPLY -> "roster.events.applied";
            case STALE -> "roster.events.stale";
            case DUPLICATE -> "roster.events.duplicate";
            case REJECTED -> "roster.events.rejected";
        };
        increment(metric, event);
    }

    public void contention(RosterEvent event) {
        increment("roster.events.contention", event);
    }

    public void strippedFields(int count, RosterEvent event) {
        if (count > 0) {
            registry.counter("roster.events.fields.stripped", tags(event)).increment(count);
        }
    }

    private void increment(String name, RosterEvent event) {
        registry.counter(name, tags(event)).increment();
    }

    private String[] tags(RosterEvent event) {
        // Tenant and owner are operational dimensions; never tag employee data.
        return new String[]{"tenant", event.tenantId(), "owner", event.owner().name()};
    }
}
