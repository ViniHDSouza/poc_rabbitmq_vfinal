package com.estudo.rabbitmq.sender.config;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

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

    // Número máximo de tentativas de conexão ao iniciar
    private static final int MAX_TENTATIVAS = 10;

    // Espera entre tentativas em milissegundos (dobra a cada falha — backoff exponencial)
    private static final long ESPERA_INICIAL_MS = 2_000;

    @Bean
    public MqttClient mqttClient() throws MqttException, InterruptedException {

        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setKeepAliveInterval(keepAliveSeconds);
        options.setConnectionTimeout(connectionTimeoutSeconds);
        options.setCleanSession(true);

        // ----------------------------------------------------------------
        // LWT — Last Will and Testament
        // Mensagem publicada pelo broker se este cliente cair inesperadamente
        // ----------------------------------------------------------------
        options.setWill(
                "pessoas/status/sender-service",
                "{\"status\":\"OFFLINE\",\"cliente\":\"sender-service-01\"}"
                        .getBytes(StandardCharsets.UTF_8),
                1,
                false
        );

        // ----------------------------------------------------------------
        // Retry com backoff exponencial
        //
        // O broker MQTT (porta 1883) pode demorar alguns segundos para
        // estar pronto após o container subir — especialmente porque o
        // plugin rabbitmq_mqtt precisa inicializar depois do RabbitMQ.
        //
        // Sem retry, a aplicação falha imediatamente se o broker ainda
        // não estiver aceitando conexões na porta 1883.
        // ----------------------------------------------------------------
        MqttException ultimoErro = null;
        long espera = ESPERA_INICIAL_MS;

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                log.info("[MQTT] Tentativa {}/{} de conexão | url={} clientId={}",
                        tentativa, MAX_TENTATIVAS, brokerUrl, clientId);
                client.connect(options);
                log.info("[MQTT] Conectado com sucesso | broker={}", brokerUrl);
                return client;
            } catch (MqttException e) {
                ultimoErro = e;
                log.warn("[MQTT] Falha na tentativa {}/{} | causa={} | aguardando {}ms...",
                        tentativa, MAX_TENTATIVAS, e.getMessage(), espera);

                if (tentativa < MAX_TENTATIVAS) {
                    Thread.sleep(espera);
                    espera = Math.min(espera * 2, 30_000); // máximo de 30s entre tentativas
                }
            }
        }

        log.error("[MQTT] Não foi possível conectar após {} tentativas", MAX_TENTATIVAS);
        throw ultimoErro;
    }
}
