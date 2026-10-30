-- Minimal booking records used by the employee deactivation listener.
-- A cancelled booking releases its seat for downstream allocation.
CREATE TABLE booking (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   VARCHAR(100) NOT NULL,
    employee_id VARCHAR(100) NOT NULL,
    travel_date DATE NOT NULL,
    seat_id     VARCHAR(100) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'BOOKED'
);

CREATE INDEX idx_booking_employee_date
    ON booking (tenant_id, employee_id, travel_date);
