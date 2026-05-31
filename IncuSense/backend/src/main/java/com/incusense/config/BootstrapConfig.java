package com.incusense.config;

import com.incusense.model.AppUser;
import com.incusense.model.Lab;
import com.incusense.repository.Repositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class BootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfig.class);

    /**
     * Creates an initial admin user (and its lab) on first boot if the users table is empty
     * and an admin username is configured. Prevents lock-out after the migration from the
     * single-admin model to the multi-user model. Idempotent.
     */
    @Bean
    ApplicationRunner bootstrapAdmin(Repositories.AppUserRepository userRepository,
                                     Repositories.LabRepository labRepository,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${incusense.security.admin-username:}") String adminUsername,
                                     @Value("${incusense.security.admin-password:}") String adminPassword,
                                     @Value("${incusense.security.operator-username:operator}") String operatorUsername,
                                     @Value("${incusense.security.operator-password:operator123}") String operatorPassword) {
        return args -> {
            if (userRepository.count() > 0) {
                return;
            }
            // 1) Admin user, in its own system lab.
            if (StringUtils.hasText(adminUsername) && StringUtils.hasText(adminPassword)) {
                Lab sysLab = labRepository.findByLabId("lab_system")
                        .orElseGet(() -> labRepository.save(new Lab("lab_system", "System Lab")));
                userRepository.save(new AppUser(adminUsername, "admin@localhost",
                        passwordEncoder.encode(adminPassword), "ADMIN", sysLab));
                log.info("[BOOTSTRAP] Created admin user '{}' in lab '{}'.", adminUsername, sysLab.getLabId());
            } else {
                log.info("[BOOTSTRAP] No admin credentials configured; skipping admin creation.");
            }

            // 2) Operator user, attached to the default firmware lab 'lab_alpha'
            //    (seeded by Flyway V2) so the default sensing-unit data is visible after login.
            if (StringUtils.hasText(operatorUsername) && StringUtils.hasText(operatorPassword)) {
                Lab alpha = labRepository.findByLabId("lab_alpha")
                        .orElseGet(() -> labRepository.save(new Lab("lab_alpha", "Default Lab")));
                userRepository.save(new AppUser(operatorUsername, "operator@localhost",
                        passwordEncoder.encode(operatorPassword), "LAB_USER", alpha));
                log.info("[BOOTSTRAP] Created operator user '{}' in lab '{}'.", operatorUsername, alpha.getLabId());
            }
        };
    }
}
