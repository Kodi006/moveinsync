package com.moveinsync.roster.api;

import java.time.Instant;

public record ApiError(String code, Instant timestamp) {
}
