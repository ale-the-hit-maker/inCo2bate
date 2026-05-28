package com.incusense.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Embeddable
public class MeasurementId implements Serializable {

    @Column(name = "hub_id", nullable = false)
    private Long hubId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected MeasurementId() {
    }

    public MeasurementId(Long hubId, Instant recordedAt) {
        this.hubId = hubId;
        this.recordedAt = recordedAt;
    }

    public Long getHubId() {
        return hubId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeasurementId that)) {
            return false;
        }
        return Objects.equals(hubId, that.hubId) && Objects.equals(recordedAt, that.recordedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hubId, recordedAt);
    }
}
