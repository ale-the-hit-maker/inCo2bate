package com.incusense.controller;

import com.incusense.dto.Dtos;
import com.incusense.repository.Repositories;
import com.incusense.security.JwtTokenProvider;
import com.incusense.service.AlertService;
import com.incusense.service.CalibrationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class Controllers {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.MeasurementRepository measurementRepository;
    private final AlertService alertService;
    private final CalibrationService calibrationService;

    public Controllers(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       Repositories.SensingHubRepository hubRepository,
                       Repositories.MeasurementRepository measurementRepository,
                       AlertService alertService,
                       CalibrationService calibrationService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.hubRepository = hubRepository;
        this.measurementRepository = measurementRepository;
        this.alertService = alertService;
        this.calibrationService = calibrationService;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Dtos.AuthResponse> login(@Valid @RequestBody Dtos.LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserDetails principal = (UserDetails) authentication.getPrincipal();
        return ResponseEntity.ok(new Dtos.AuthResponse(jwtTokenProvider.createToken(principal), "Bearer", principal.getUsername()));
    }

    @GetMapping("/hubs")
    public List<Dtos.HubResponse> hubs() {
        return hubRepository.findAll().stream().map(Dtos.HubResponse::from).toList();
    }

    @GetMapping("/measurements/latest")
    public List<Dtos.MeasurementResponse> latest(@RequestParam(defaultValue = "100") int limit) {
        return measurementRepository.findAllByOrderByIdRecordedAtDesc(PageRequest.of(0, sanitizeLimit(limit)))
                .stream()
                .map(Dtos.MeasurementResponse::from)
                .toList();
    }

    @GetMapping("/hubs/{hubKey}/measurements")
    public List<Dtos.MeasurementResponse> measurementsForHub(
            @PathVariable String hubKey,
            @RequestParam(defaultValue = "200") int limit) {
        return measurementRepository.findByHubHubKeyOrderByIdRecordedAtDesc(hubKey, PageRequest.of(0, sanitizeLimit(limit)))
                .stream()
                .map(Dtos.MeasurementResponse::from)
                .toList();
    }

    @GetMapping("/alerts")
    public List<Dtos.AlertResponse> alerts() {
        return alertService.recentAlerts();
    }

    @GetMapping("/calibration/{hubKey}")
    public Dtos.CalibrationResponse calibration(@PathVariable String hubKey) {
        return calibrationService.getCalibration(hubKey);
    }

    @PostMapping("/calibration/{hubKey}")
    public Dtos.CalibrationResponse updateCalibration(
            @PathVariable String hubKey,
            @RequestBody Dtos.CalibrationRequest request) {
        return calibrationService.updateCalibration(hubKey, request);
    }

    private int sanitizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 1000));
    }
}
