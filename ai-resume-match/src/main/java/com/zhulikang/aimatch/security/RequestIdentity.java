package com.zhulikang.aimatch.security;

public final class RequestIdentity {
    private static final ThreadLocal<String> CURRENT_OWNER = new ThreadLocal<>();

    private RequestIdentity() {
    }

    public static String currentOwnerId() {
        String ownerId = CURRENT_OWNER.get();
        if (ownerId == null) {
            throw new IllegalStateException("Request identity has not been established");
        }
        return ownerId;
    }

    public static void set(String ownerId) {
        CURRENT_OWNER.set(OwnerId.requireValid(ownerId));
    }

    public static void clear() {
        CURRENT_OWNER.remove();
    }
}
