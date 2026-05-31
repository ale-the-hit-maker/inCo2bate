package com.incusense.controller;

import com.incusense.dto.Dtos;
import com.incusense.model.Lab;
import com.incusense.model.NotificationContact;
import com.incusense.repository.Repositories;
import com.incusense.security.AuthenticatedLabUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/notification-contacts")
public class NotificationController {

    private static final Set<String> LEVELS = Set.of("INFO", "WARN", "CRITICAL");

    private final Repositories.NotificationContactRepository contactRepository;
    private final Repositories.LabRepository labRepository;

    public NotificationController(Repositories.NotificationContactRepository contactRepository,
                                  Repositories.LabRepository labRepository) {
        this.contactRepository = contactRepository;
        this.labRepository = labRepository;
    }

    @GetMapping
    public List<Dtos.ContactResponse> list(@AuthenticationPrincipal AuthenticatedLabUser principal) {
        return contactRepository.findByLab_LabId(principal.getLabId()).stream()
                .map(Dtos.ContactResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<Dtos.ContactResponse> create(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                                       @Valid @RequestBody Dtos.ContactRequest request) {
        String channel = normalizeChannel(request.channel());
        String minLevel = normalizeLevel(request.minLevel());
        validateTarget(channel, request.target());
        Lab lab = labRepository.findByLabId(principal.getLabId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lab not found"));
        NotificationContact saved = contactRepository.save(new NotificationContact(
                lab, request.label(), channel, request.target(), minLevel, request.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.ContactResponse.from(saved));
    }

    @PutMapping("/{id}")
    public Dtos.ContactResponse update(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody Dtos.ContactRequest request) {
        NotificationContact contact = ownedContact(id, principal);
        String channel = normalizeChannel(request.channel());
        String minLevel = normalizeLevel(request.minLevel());
        validateTarget(channel, request.target());
        contact.setLabel(request.label());
        contact.setChannel(channel);
        contact.setTarget(request.target());
        contact.setMinLevel(minLevel);
        contact.setEnabled(request.enabled());
        return Dtos.ContactResponse.from(contactRepository.save(contact));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedLabUser principal,
                                       @PathVariable Long id) {
        NotificationContact contact = ownedContact(id, principal);
        contactRepository.delete(contact);
        return ResponseEntity.noContent().build();
    }

    private NotificationContact ownedContact(Long id, AuthenticatedLabUser principal) {
        NotificationContact contact = contactRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
        if (!contact.getLab().getLabId().equals(principal.getLabId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contact not in your lab");
        }
        return contact;
    }

    private String normalizeChannel(String channel) {
        String c = StringUtils.hasText(channel) ? channel.toUpperCase() : "EMAIL";
        if (!"EMAIL".equals(c)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only EMAIL channel is supported in v1");
        }
        return c;
    }

    private String normalizeLevel(String level) {
        String l = StringUtils.hasText(level) ? level.toUpperCase() : "WARN";
        if (!LEVELS.contains(l)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minLevel must be one of INFO, WARN, CRITICAL");
        }
        return l;
    }

    private void validateTarget(String channel, String target) {
        if ("EMAIL".equals(channel) && (!target.contains("@") || target.startsWith("@") || target.endsWith("@"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email address");
        }
    }
}
