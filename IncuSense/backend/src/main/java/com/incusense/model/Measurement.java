package com.incusense.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "measurements")
public class Measurement {

    @EmbeddedId
    private MeasurementId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("hubId")
    @JoinColumn(name = "hub_id", nullable = false)
    private SensingHub hub;

    @Column(name = "co2_ppm", nullable = false)
    private Double co2Ppm;

    @Column(name = "heater_temp", nullable = false)
    private Double heaterTemp;

    @Column(name = "env_temp", nullable = false)
    private Double envTemp;

    @Column(name = "env_hum", nullable = false)
    private Double envHum;

    @Column(name = "rail_12v", nullable = false)
    private Double rail12v;

    @Column(name = "raw_adc")
    private Integer rawAdc;

    @Column(name = "sensor_response")
    private Double sensorResponse;

    protected Measurement() {
    }

    public Measurement(SensingHub hub, Instant recordedAt, Double co2Ppm, Double heaterTemp,
                       Double envTemp, Double envHum, Double rail12v) {
        this(hub, recordedAt, co2Ppm, heaterTemp, envTemp, envHum, rail12v, null, null);
    }

    public Measurement(SensingHub hub, Instant recordedAt, Double co2Ppm, Double heaterTemp,
                       Double envTemp, Double envHum, Double rail12v, Integer rawAdc, Double sensorResponse) {
        this.hub = hub;
        this.id = new MeasurementId(hub.getId(), recordedAt);
        this.co2Ppm = co2Ppm;
        this.heaterTemp = heaterTemp;
        this.envTemp = envTemp;
        this.envHum = envHum;
        this.rail12v = rail12v;
        this.rawAdc = rawAdc;
        this.sensorResponse = sensorResponse;
    }

    public MeasurementId getId() {
        return id;
    }

    public SensingHub getHub() {
        return hub;
    }

    public Instant getRecordedAt() {
        return id.getRecordedAt();
    }

    public Double getCo2Ppm() {
        return co2Ppm;
    }

    public Double getHeaterTemp() {
        return heaterTemp;
    }

    public Double getEnvTemp() {
        return envTemp;
    }

    public Double getEnvHum() {
        return envHum;
    }

    public Double getRail12v() {
        return rail12v;
    }

    public Integer getRawAdc() {
        return rawAdc;
    }

    public Double getSensorResponse() {
        return sensorResponse;
    }
}
