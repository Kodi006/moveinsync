package com.moveinsync.roster.security;

public record TenantPrincipal(String tenantId, Role role) {
}
