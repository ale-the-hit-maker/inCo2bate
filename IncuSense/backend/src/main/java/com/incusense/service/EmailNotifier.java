package com.incusense.service;

import com.incusense.model.Alert;
import com.incusense.model.NotificationContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final String mailHost;

    public EmailNotifier(ObjectProvider<JavaMailSender> mailSenderProvider,
                         @Value("${incusense.alerting.from-address:incusense@localhost}") String fromAddress,
                         @Value("${spring.mail.host:}") String mailHost) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.mailHost = mailHost;
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    @Async
    public void notify(NotificationContact contact, Alert alert) {
        JavaMailSender sender = (mailHost == null || mailHost.isBlank()) ? null : mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.info("[ALERT-EMAIL] (disabled, no SMTP configured) would notify {} -> [{}] {}/{}: {}",
                    contact.getTarget(), alert.getLevel(), alert.getHub().getHubKey(), alert.getRuleKey(), alert.getMessage());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(contact.getTarget());
            msg.setSubject("[IncuSense][" + alert.getLevel() + "] " + alert.getHub().getHubKey());
            msg.setText(String.format("Alert: %s%nHub: %s%nRule: %s%nLevel: %s%nTime: %s%n",
                    alert.getMessage(), alert.getHub().getHubKey(), alert.getRuleKey(),
                    alert.getLevel(), alert.getCreatedAt()));
            sender.send(msg);
            log.info("[ALERT-EMAIL] sent to {} for {}/{}", contact.getTarget(), alert.getHub().getHubKey(), alert.getRuleKey());
        } catch (Exception ex) {
            log.warn("[ALERT-EMAIL] failed to send to {}: {}", contact.getTarget(), ex.getMessage());
        }
    }
}
