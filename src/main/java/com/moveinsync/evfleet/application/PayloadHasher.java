package com.moveinsync.evfleet.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moveinsync.evfleet.domain.DomainException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Canonical SHA-256 of a request payload, used to tell a true duplicate from a reused idempotency key. */
@Component
public class PayloadHasher {

    private final ObjectMapper canonical;

    /** Copies the JSON mapper with map keys sorted, so equal payloads hash equally. */
    public PayloadHasher(ObjectMapper mapper) {
        this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** SHA-256 of the payload's canonical JSON. */
    public String hash(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(payload)));
        } catch (Exception e) {
            throw new DomainException("HASH_FAILED", 500);
        }
    }
}
