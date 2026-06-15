package com.incusense.controller;

import com.incusense.dto.Dtos;
import com.incusense.model.CalibrationEvent;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import com.incusense.service.AutoCalibrationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Endpoint dell'auto-calibrazione (lab-scoped): consultazione del lifecycle degli eventi e
 * trigger manuale di una correzione. Coerente con gli altri controller (@AuthenticationPrincipal,
 * isolamento per lab).
 */
@RestController
@RequestMapping("/api")
public class AutoCalibrationController {

    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.SensorHealthRepository healthRepository;
    private final Repositories.CalibrationEventRepository eventRepository;
    private final AutoCalibrationService autoCalibrationService;

    public AutoCalibrationController(Repositories.SensingHubRepository hubRepository,
                                    Repositories.SensorHealthRepository healthRepository,
                                    Repositories.CalibrationEventRepository eventRepository,
                                    AutoCalibrationService autoCalibrationService) {
        this.hubRepository = hubRepository;
        this.healthRepository = healthRepository;
        this.eventRepository = eventRepository;
        this.autoCalibrationService = autoCalibrationService;
    }

    @GetMapping("/calibration-events")
    public List<Dtos.CalibrationEventResponse> events(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                                      @RequestParam(defaultValue = "100") int limit) {
        int sane = Math.max(1, Math.min(limit, 500));
        return eventRepository
                .findByHub_Lab_LabIdOrderByCreatedAtDesc(principal.getLabId(), PageRequest.of(0, sane))
                .stream()
                .map(Dtos.CalibrationEventResponse::from)
                .toList();
    }

    @PostMapping("/hubs/{hubKey}/autocal/trigger")
    public Dtos.CalibrationEventResponse trigger(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                                 @PathVariable String hubKey) {
        if (!autoCalibrationService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Auto-calibrazione disabilitata (incusense.autocal.enabled=false)");
        }
        SensingHub hub = requireHubInLab(hubKey, principal.getLabId());
        SensorHealth health = healthRepository.findById(hub.getId()).orElse(null);
        try {
            CalibrationEvent ev = autoCalibrationService.triggerManual(hub, health);
            return Dtos.CalibrationEventResponse.from(ev);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private SensingHub requireHubInLab(String hubKey, String labId) {
        return hubRepository.findByHubKeyAndLab_LabId(hubKey, labId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Hub not in your lab"));
    }
}
