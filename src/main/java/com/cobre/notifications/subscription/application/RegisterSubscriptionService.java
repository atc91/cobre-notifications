package com.cobre.notifications.subscription.application;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import com.cobre.notifications.subscription.domain.port.in.RegisterSubscriptionUseCase;
import com.cobre.notifications.subscription.domain.port.out.SubscriptionStorePort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Use-case implementation for registering a subscription: it delegates straight to the
 * {@link SubscriptionStorePort}, whose idempotent upsert makes re-seeding safe. Depends only on its
 * own domain ports (never an adapter), per the Dependency Rule.
 */
@Service
public class RegisterSubscriptionService implements RegisterSubscriptionUseCase {

    private final SubscriptionStorePort store;

    public RegisterSubscriptionService(SubscriptionStorePort store) {
        this.store = store;
    }

    @Override
    public Mono<Void> register(SubscriptionRegistration registration) {
        return store.save(registration);
    }
}
