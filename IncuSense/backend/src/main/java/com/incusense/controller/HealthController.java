package com.incusense.controller;

import com.incusense.dto.Dtos;
import com.incusense.model.DriftReferenceCurve;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.SensorHealthRepository healthRepository;
    private final Repositories.DriftReferenceCurveRepository curveRepository;

    public HealthController(Repositories.SensingHubRepository hubRepository,
                            Repositories.SensorHealthRepository healthRepository,
                            Repositories.DriftReferenceCurveRepository curveRepository) {
        this.hubRepository = hubRepository;
        this.healthRepository = healthRepository;
        this.curveRepository = curveRepository;
    }

    @GetMapping("/hubs/{hubKey}/health")
    public Dtos.HealthResponse health(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                      @PathVariable String hubKey) {
        SensingHub hub = requireHubInLab(hubKey, principal.getLabId());
        return healthRepository.findById(hub.getId())
                .map(Dtos.HealthResponse::from)
                .orElse(new Dtos.HealthResponse(hubKey, "UNKNOWN", null, 0.0, null, null, null, null));
    }

    @GetMapping("/drift-curves")
    public List<Dtos.DriftCurveResponse> curves() {
        return curveRepository.findAll().stream()
                .map(c -> new Dtos.DriftCurveResponse(c.getId(), c.getSensorType(), c.getRefVoltageV(),
                        c.getRefTempC(), c.getDescription(), c.getPointsJson()))
                .toList();
    }

    @PostMapping("/drift-curves")
    public ResponseEntity<Dtos.DriftCurveResponse> createCurve(@Valid @RequestBody Dtos.DriftCurveRequest request) {
        DriftReferenceCurve c = curveRepository.save(new DriftReferenceCurve(
                request.sensorType(), request.refVoltageV(), request.refTempC(),
                request.description(), request.pointsJson()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Dtos.DriftCurveResponse(
                c.getId(), c.getSensorType(), c.getRefVoltageV(), c.getRefTempC(), c.getDescription(), c.getPointsJson()));
    }

    @PostMapping("/hubs/{hubKey}/health/assign-curve")
    public Dtos.HealthResponse assignCurve(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                           @PathVariable String hubKey,
                                           @RequestBody Dtos.AssignCurveRequest request) {
        SensingHub hub = requireHubInLab(hubKey, principal.getLabId());
        SensorHealth health = healthRepository.findById(hub.getId()).orElseGet(() -> new SensorHealth(hub));
        if (request.curveId() != null) {
            DriftReferenceCurve curve = curveRepository.findById(request.curveId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown curve id"));
            health.setCurve(curve);
        }
        if (request.installResponse() != null) {
            health.setInstallResponse(request.installResponse());
        }
        return Dtos.HealthResponse.from(healthRepository.save(health));
    }

    private SensingHub requireHubInLab(String hubKey, String labId) {
        return hubRepository.findByHubKeyAndLab_LabId(hubKey, labId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Hub not in your lab"));
    }
}
