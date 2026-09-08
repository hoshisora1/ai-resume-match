package com.zhulikang.aimatch.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdentityTest {
    @AfterEach
    void clear() {
        RequestIdentity.clear();
    }

    @Test
    void missingIdentityFailsInsteadOfSelectingTheLegacyOwner() {
        assertThatThrownBy(RequestIdentity::currentOwnerId)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Request identity has not been established");
    }

    @Test
    void identityIsExplicitAndDoesNotLeakToAnotherThreadOrAfterCleanup() {
        RequestIdentity.set("a".repeat(64));
        assertThat(RequestIdentity.currentOwnerId()).isEqualTo("a".repeat(64));
        CompletableFuture.runAsync(() ->
            assertThatThrownBy(RequestIdentity::currentOwnerId)
                .isInstanceOf(IllegalStateException.class)
        ).join();
        RequestIdentity.clear();
        assertThatThrownBy(RequestIdentity::currentOwnerId)
            .isInstanceOf(IllegalStateException.class);
    }
}
