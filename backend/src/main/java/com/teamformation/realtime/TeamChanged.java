package com.teamformation.realtime;

import java.util.UUID;

/** Domain event published inside a transaction; delivered to clients only AFTER the commit. */
public record TeamChanged(UUID teamId, UUID hackathonId, String type) {}
