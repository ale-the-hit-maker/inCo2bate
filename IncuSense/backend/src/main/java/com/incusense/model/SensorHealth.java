package com.incusense.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sensor_health")
public class SensorHealth {

    @Id
    @Column(name = "hub_id")
    private Long hubId;

    @MapsId
    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hub_id")
    private SensingHub hub;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "curve_id")
    private DriftReferenceCurve curve;

    @Column(name = "install_response")
    private Double installResponse;

    @Column(name = "operating_hours", nullable = false)
    private Double operatingHours = 0.0;

    @Column(name = "last_response")
    private Double lastResponse;

    @Column(name = "observed_drift_pct")
    private Double observedDriftPct;

    @Column(name = "projected_eol_at")
    private Instant projectedEolAt;

    @Column(name = "health_status", nullable = false, length = 16)
    private String healthStatus = "OK";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SensorHealth() {
    }

    public SensorHealth(SensingHub hub) {
        this.hub = hub;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (operatingHours == null) {
            operatingHours = 0.0;
        }
        if (healthStatus == null) {
            healthStatus = "OK";
        }
    }

    public Long getHubId() {
        return hubId;
    }

    public SensingHub getHub() {
        return hub;
    }

    public DriftReferenceCurve getCurve() {
        return curve;
    }

    public void setCurve(DriftReferenceCurve curve) {
        this.curve = curve;
    }

    public Double getInstallResponse() {
        return installResponse;
    }

    public void setInstallResponse(Double installResponse) {
        this.installResponse = installResponse;
    }

    public Double getOperatingHours() {
        return operatingHours;
    }

    public void setOperatingHours(Double operatingHours) {
        this.operatingHours = operatingHours;
    }

    public Double getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(Double lastResponse) {
        this.lastResponse = lastResponse;
    }

    public Double getObservedDriftPct() {
        return observedDriftPct;
    }

    public void setObservedDriftPct(Double observedDriftPct) {
        this.observedDriftPct = observedDriftPct;
    }

    public Instant getProjectedEolAt() {
        return projectedEolAt;
    }

    public void setProjectedEolAt(Instant projectedEolAt) {
        this.projectedEolAt = projectedEolAt;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
