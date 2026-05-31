package com.incusense.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "drift_reference_curves")
public class DriftReferenceCurve {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_type", nullable = false, length = 64)
    private String sensorType;

    @Column(name = "ref_voltage_v")
    private Double refVoltageV;

    @Column(name = "ref_temp_c")
    private Double refTempC;

    @Column(name = "description", length = 512)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points_json", nullable = false)
    private String pointsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DriftReferenceCurve() {
    }

    public DriftReferenceCurve(String sensorType, Double refVoltageV, Double refTempC, String description, String pointsJson) {
        this.sensorType = sensorType;
        this.refVoltageV = refVoltageV;
        this.refTempC = refTempC;
        this.description = description;
        this.pointsJson = pointsJson;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getSensorType() {
        return sensorType;
    }

    public Double getRefVoltageV() {
        return refVoltageV;
    }

    public Double getRefTempC() {
        return refTempC;
    }

    public String getDescription() {
        return description;
    }

    public String getPointsJson() {
        return pointsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
