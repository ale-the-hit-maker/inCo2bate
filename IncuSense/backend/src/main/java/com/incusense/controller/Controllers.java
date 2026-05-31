package com.incusense.controller;

import com.incusense.dto.Dtos;
import com.incusense.model.AppUser;
import com.incusense.model.Lab;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import com.incusense.security.JwtTokenProvider;
import com.incusense.service.AlertService;
import com.incusense.service.CalibrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class Controllers {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Repositories.AppUserRepository userRepository;
    private final Repositories.LabRepository labRepository;
    private final Repositories.SensingHubRepository hubRepository;
    private final Repositories.MeasurementRepository measurementRepository;
    private final AlertService alertService;
    private final CalibrationService calibrationService;
    private final boolean registrationEnabled;

    public Controllers(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       Repositories.AppUserRepository userRepository,
                       Repositories.LabRepository labRepository,
                       Repositories.SensingHubRepository hubRepository,
                       Repositories.MeasurementRepository measurementRepository,
                       AlertService alertService,
                       CalibrationService calibrationService,
                       @Value("${incusense.security.registration-enabled:false}") boolean registrationEnabled) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.labRepository = labRepository;
        this.hubRepository = hubRepository;
        this.measurementRepository = measurementRepository;
        this.alertService = alertService;
        this.calibrationService = calibrationService;
        this.registrationEnabled = registrationEnabled;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Dtos.AuthResponse> login(@Valid @RequestBody Dtos.LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        AppUser user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
        String token = jwtTokenProvider.createToken(user.getUsername(), user.getLab().getLabId(), user.getRole());
        return ResponseEntity.ok(new Dtos.AuthResponse(token, "Bearer", user.getUsername(), user.getLab().getLabId()));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<Dtos.AuthResponse> register(@Valid @RequestBody Dtos.RegisterRequest request) {
        // Self-registration is disabled in the prototype (re-enable via incusense.security.registration-enabled=true).
        if (!registrationEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La registrazione e' disabilitata in questa versione del prototipo");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        Lab lab;
        if (StringUtils.hasText(request.existingLabId())) {
            lab = labRepository.findByLabId(request.existingLabId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown lab id"));
        } else if (StringUtils.hasText(request.labDisplayName())) {
            lab = labRepository.save(new Lab(generateLabSlug(), request.labDisplayName()));
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either labDisplayName or existingLabId");
        }
        AppUser user = userRepository.save(new AppUser(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                "LAB_USER",
                lab));
        String token = jwtTokenProvider.createToken(user.getUsername(), lab.getLabId(), user.getRole());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Dtos.AuthResponse(token, "Bearer", user.getUsername(), lab.getLabId()));
    }

    @GetMapping("/lab")
    public Dtos.LabResponse lab(@AuthenticationPrincipal AuthenticatedLabUser principal) {
        Lab lab = labRepository.findByLabId(principal.getLabId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lab not found"));
        return Dtos.LabResponse.from(lab);
    }

    @GetMapping("/hubs")
    public List<Dtos.HubResponse> hubs(@AuthenticationPrincipal AuthenticatedLabUser principal) {
        return hubRepository.findByLab_LabId(principal.getLabId()).stream().map(Dtos.HubResponse::from).toList();
    }

    @GetMapping("/measurements/latest")
    public List<Dtos.MeasurementResponse> latest(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return measurementRepository.findByLabOrderByRecordedAtDesc(principal.getLabId(), PageRequest.of(0, sanitizeLimit(limit)))
                .stream()
                .map(Dtos.MeasurementResponse::from)
                .toList();
    }

    @GetMapping("/hubs/{hubKey}/measurements")
    public List<Dtos.MeasurementResponse> measurementsForHub(
            @AuthenticationPrincipal AuthenticatedLabUser principal,
            @PathVariable String hubKey,
            @RequestParam(defaultValue = "200") int limit) {
        requireHubInLab(hubKey, principal.getLabId());
        return measurementRepository.findByHubAndLabOrderByRecordedAtDesc(hubKey, principal.getLabId(), PageRequest.of(0, sanitizeLimit(limit)))
                .stream()
                .map(Dtos.MeasurementResponse::from)
                .toList();
    }

    @GetMapping("/measurements/history")
    public List<Dtos.HistoryResponse> history(@AuthenticationPrincipal AuthenticatedLabUser principal) {
        return measurementRepository.find6MonthHistory(principal.getLabId())
                .stream()
                .map(p -> new Dtos.HistoryResponse(
                        p.getRecordedAt(),
                        p.getCo2Ppm() != null ? p.getCo2Ppm() : 0.0,
                        p.getHeaterTemp() != null ? p.getHeaterTemp() : 0.0,
                        p.getEnvTemp() != null ? p.getEnvTemp() : 0.0,
                        p.getEnvHum() != null ? p.getEnvHum() : 0.0,
                        p.getRail12v() != null ? p.getRail12v() : 0.0))
                .toList();
    }

    @GetMapping("/alerts")
    public List<Dtos.AlertResponse> alerts(@AuthenticationPrincipal AuthenticatedLabUser principal) {
        return alertService.recentAlerts(principal.getLabId());
    }

    @GetMapping("/calibration/{hubKey}")
    public Dtos.CalibrationResponse calibration(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                                @PathVariable String hubKey) {
        requireHubInLab(hubKey, principal.getLabId());
        return calibrationService.getCalibration(hubKey);
    }

    @PostMapping("/calibration/{hubKey}")
    public Dtos.CalibrationResponse updateCalibration(
            @AuthenticationPrincipal AuthenticatedLabUser principal,
            @PathVariable String hubKey,
            @RequestBody Dtos.CalibrationRequest request) {
        requireHubInLab(hubKey, principal.getLabId());
        return calibrationService.updateCalibration(hubKey, request);
    }

    private void requireHubInLab(String hubKey, String labId) {
        hubRepository.findByHubKeyAndLab_LabId(hubKey, labId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Hub not in your lab"));
    }

    private String generateLabSlug() {
        for (int i = 0; i < 5; i++) {
            String slug = "lab_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (labRepository.findByLabId(slug).isEmpty()) {
                return slug;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not allocate lab id");
    }

    private int sanitizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 1000));
    }
}
