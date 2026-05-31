package com.incusense.config;

import com.incusense.service.MqttIngestionService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;

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
            @Value("${incusense.mqtt.status-topic:incusense/labs/+/hubs/+/status}") String statusTopic) {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId + "-inbound", mqttClientFactory, telemetryTopic, statusTopic);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel);
        return adapter;
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
