package com.teamformation.realtime;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Fires only after a successful COMMIT, so clients never hear about rolled-back changes. */
@Component
public class TeamEventPublisher {
    private final SseHub hub;
    public TeamEventPublisher(SseHub hub) { this.hub = hub; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TeamChanged e) {
        Map<String, Object> payload = Map.of("type", e.type(), "teamId", e.teamId().toString());
        hub.publish("hackathon:" + e.hackathonId(), payload);
        hub.publish("team:" + e.teamId(), payload);
    }
}
