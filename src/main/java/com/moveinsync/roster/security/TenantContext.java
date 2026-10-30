package com.moveinsync.roster.security;

public final class TenantContext {

    private static final ThreadLocal<TenantPrincipal> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static void set(TenantPrincipal principal) {
        CURRENT.set(principal);
    }

    public static TenantPrincipal current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
