package com.moveinsync.roster.outbox;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class InProcessChangeEventPublisher implements ChangeEventPublisher {

    private final CopyOnWriteArrayList<Consumer<ChangeEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void addListener(Consumer<ChangeEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void publish(ChangeEvent event) {
        for (Consumer<ChangeEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
