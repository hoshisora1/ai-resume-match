package com.zhulikang.aimatch.support;

import com.zhulikang.aimatch.security.OwnerId;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Explicit identity for tests whose persisted fixtures use the migration owner. */
public final class RequestOwnerExtension implements BeforeEachCallback, AfterEachCallback {
    public static final String OWNER_ID = OwnerId.LEGACY;

    @Override
    public void beforeEach(ExtensionContext context) {
        RequestIdentity.set(OWNER_ID);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        RequestIdentity.clear();
    }
}
