package com.incusense.config;

import com.incusense.service.MqttIngestionService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

@Configuration
public class MqttConfig {

    @Bean
    MqttPahoClientFactory mqttClientFactory(
            @Value("${incusense.mqtt.broker-url}") String brokerUrl) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(
            MqttPahoClientFactory mqttClientFactory,
            MessageChannel mqttInputChannel,
            @Value("${incusense.mqtt.client-id}") String clientId,
            @Value("${incusense.mqtt.topic}") String telemetryTopic,
            @Value("${incusense.mqtt.status-topic:incusense/labs/+/hubs/+/status}") String statusTopic,
            @Value("${incusense.mqtt.ack-topic:incusense/labs/+/hubs/+/ack}") String ackTopic) {
        // Sottoscrizione anche al topic .../ack (additivo): gli ACK di calibrazione dei nodi
        // arrivano sullo stesso canale inbound e vengono instradati da MqttIngestionService.
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId + "-inbound", mqttClientFactory,
                        telemetryTopic, statusTopic, ackTopic);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel);
        return adapter;
    }

    // ── Outbound: la piattaforma pubblica i comandi di calibrazione verso i nodi ──
    // Il topic per-messaggio (.../commands del singolo hub) e' impostato nell'header
    // MqttHeaders.TOPIC da MqttCommandTransport; defaultTopic e' solo un fallback.
    @Bean
    MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    MessageHandler mqttOutbound(
            MqttPahoClientFactory mqttClientFactory,
            @Value("${incusense.mqtt.client-id}") String clientId) {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(clientId + "-outbound", mqttClientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(1);
        handler.setDefaultTopic("incusense/commands/unrouted");
        return handler;
    }

    @Bean
    IntegrationFlow mqttInboundFlow(MessageChannel mqttInputChannel, MqttIngestionService ingestionService) {
        return IntegrationFlow.from(mqttInputChannel)
                .handle(message -> {
                    String topic = String.valueOf(message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
                    ingestionService.ingest(topic, String.valueOf(message.getPayload()));
                })
                .get();
    }
}
