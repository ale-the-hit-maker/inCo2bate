package com.incusense.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Implementazione MQTT di {@link CommandTransport}: instrada il payload sul canale di
 * integrazione {@code mqttOutboundChannel} (configurato in {@code MqttConfig}), impostando
 * il topic per-messaggio nell'header {@link MqttHeaders#TOPIC}.
 */
@Component
public class MqttCommandTransport implements CommandTransport {

    private static final Logger log = LoggerFactory.getLogger(MqttCommandTransport.class);

    private final MessageChannel mqttOutboundChannel;

    public MqttCommandTransport(@Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel) {
        this.mqttOutboundChannel = mqttOutboundChannel;
    }

    @Override
    public boolean publish(String topic, String jsonPayload) {
        try {
            return mqttOutboundChannel.send(MessageBuilder.withPayload(jsonPayload)
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .build());
        } catch (Exception ex) {
            log.warn("[AUTOCAL] command publish failed topic={} payload={}", topic, jsonPayload, ex);
            return false;
        }
    }
}
