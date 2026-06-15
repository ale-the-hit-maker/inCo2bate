package com.incusense.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Ciclo di vita di un comando di calibrazione inviato dall'auto-calibrazione a un nodo.
 * Stati: PENDING (comando pubblicato) -> ACKED (nodo conferma) -> VERIFIED|FAILED
 * (esito del controllo post-cal). SKIPPED se un guard-rail ha bloccato l'azione.
 * L'{@code id} dell'evento e' anche l'{@code event_id} inviato al firmware (correlazione ACK).
 */
@Entity
@Table(name = "calibration_events")
public class CalibrationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "hub_id", nullable = false)
    private SensingHub hub;

    @Column(name = "command", nullable = false, length = 16)
    private String command;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "requested_offset_ppm")
    private Double requestedOffsetPpm;

    @Column(name = "drift_pct_at_request")
    private Double driftPctAtRequest;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected CalibrationEvent() {
    }

    public CalibrationEvent(SensingHub hub, String command, String status,
                            Double requestedOffsetPpm, Double driftPctAtRequest, String reason) {
        this.hub = hub;
        this.command = command;
        this.status = status;
        this.requestedOffsetPpm = requestedOffsetPpm;
        this.driftPctAtRequest = driftPctAtRequest;
        this.reason = reason;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    public Long getId() {
        return id;
    }

    public SensingHub getHub() {
        return hub;
    }

    public String getCommand() {
        return command;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Double getRequestedOffsetPpm() {
        return requestedOffsetPpm;
    }

    public Double getDriftPctAtRequest() {
        return driftPctAtRequest;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Instant ackedAt) {
        this.ackedAt = ackedAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
}
