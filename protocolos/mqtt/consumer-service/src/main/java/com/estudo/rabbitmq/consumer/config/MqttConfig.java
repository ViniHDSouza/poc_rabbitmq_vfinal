package com.estudo.rabbitmq.consumer.config;

import com.estudo.rabbitmq.consumer.handler.MensagemHandler;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    private static final int MAX_TENTATIVAS  = 10;
    private static final long ESPERA_INICIAL_MS = 2_000;

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.keep-alive-seconds}")
    private int keepAliveSeconds;

    @Value("${mqtt.connection-timeout-seconds}")
    private int connectionTimeoutSeconds;

    @Value("${mqtt.clean-session}")
    private boolean cleanSession;

    @Bean
    public MqttClient mqttClient(MensagemHandler mensagemHandler) throws MqttException, InterruptedException {

        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setKeepAliveInterval(keepAliveSeconds);
        options.setConnectionTimeout(connectionTimeoutSeconds);
        options.setCleanSession(cleanSession);

        // LWT do consumer
        options.setWill(
                "pessoas/status/consumer-service",
                "{\"status\":\"OFFLINE\",\"cliente\":\"consumer-service-01\"}".getBytes(),
                1,
                false
        );

        // ----------------------------------------------------------------
        // MqttCallback — notificações do broker para este cliente
        // ----------------------------------------------------------------
        client.setCallback(new MqttCallback() {

            @Override
            public void connectionLost(Throwable cause) {
                log.error("[MQTT] Conexão perdida | causa={}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                mensagemHandler.processar(topic, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                log.debug("[MQTT] Entrega confirmada | token={}", token.getMessageId());
            }
        });

        // ----------------------------------------------------------------
        // Retry com backoff exponencial
        //
        // O plugin rabbitmq_mqtt inicializa após o RabbitMQ principal.
        // A porta 1883 pode levar alguns segundos a mais para estar pronta.
        // ----------------------------------------------------------------
        MqttException ultimoErro = null;
        long espera = ESPERA_INICIAL_MS;

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                log.info("[MQTT] Tentativa {}/{} de conexão | url={} clientId={}",
                        tentativa, MAX_TENTATIVAS, brokerUrl, clientId);
                client.connect(options);
                log.info("[MQTT] Conectado | cleanSession={}", cleanSession);
                break; // conectou — sai do loop
            } catch (MqttException e) {
                ultimoErro = e;
                log.warn("[MQTT] Falha na tentativa {}/{} | causa={} | aguardando {}ms...",
                        tentativa, MAX_TENTATIVAS, e.getMessage(), espera);

                if (tentativa == MAX_TENTATIVAS) {
                    log.error("[MQTT] Não foi possível conectar após {} tentativas", MAX_TENTATIVAS);
                    throw ultimoErro;
                }

                Thread.sleep(espera);
                espera = Math.min(espera * 2, 30_000);
            }
        }

        // ----------------------------------------------------------------
        // Assina o tópico pessoas/# com QoS 1
        //
        // pessoas/#  → casa pessoas/cadastro, pessoas/atualizacao,
        //               pessoas/exclusao, pessoas/status/...
        // QoS da assinatura = máximo aceito; entregue com
        //   min(QoS_publicação, QoS_assinatura)
        // ----------------------------------------------------------------
        int qosAssinatura = 1;
        client.subscribe("pessoas/#", qosAssinatura);
        log.info("[MQTT] Assinado | tópico=pessoas/# qos={}", qosAssinatura);

        return client;
    }
}
