# MoveInSync HR Roster Sync

## Outbox delivery

Outbox delivery is at least once. If the process publishes an event and stops before
marking its row as published, the next poll can deliver it again. Consumers must make
their event handling idempotent.
