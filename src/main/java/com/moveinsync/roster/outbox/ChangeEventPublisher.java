package com.moveinsync.roster.outbox;

import java.util.function.Consumer;

/**
 * Broker-neutral publication boundary. Kafka is the production target; this case study
 * uses in-process listeners to keep the implementation runnable without a broker.
 */
public interface ChangeEventPublisher {

    void addListener(Consumer<ChangeEvent> listener);

    void publish(ChangeEvent event);
}
