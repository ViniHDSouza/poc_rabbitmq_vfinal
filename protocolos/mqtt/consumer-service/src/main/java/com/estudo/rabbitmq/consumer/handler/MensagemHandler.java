package com.estudo.rabbitmq.consumer.handler;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processa mensagens MQTT recebidas e inspeciona todas as suas propriedades.
 *
 * A mensagem MQTT é intencionalmente mais simples que a AMQP:
 * não tem headers, não tem messageId, não tem correlationId, não tem timestamp.
 * O protocolo MQTT é projetado para ser leve — o payload é bytes puros.
 *
 * Propriedades disponíveis na MqttMessage:
 *   qos        → nível de QoS negociado (min entre publisher e subscriber)
 *   retained   → true se for a mensagem retida do tópico (não publicada agora)
 *   duplicate  → true se for reenvio (QoS 1/2, DUP=1 no pacote MQTT)
 *   id         → packet identifier (usado internamente para QoS 1/2)
 *   payload    → array de bytes — o conteúdo real
 *
 * O tópico NÃO faz parte da MqttMessage — é informado separadamente
 * no callback messageArrived(String topic, MqttMessage message).
 */
@Component
public class MensagemHandler {

    private static final Logger log = LoggerFactory.getLogger(MensagemHandler.class);

    private final ObjectMapper objectMapper;

    public MensagemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void processar(String topic, MqttMessage message) {
        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [MQTT] Mensagem recebida");
        log.info("╠══════════════════════════════════════════════════════════════════════");

        // ── TÓPICO ──────────────────────────────────────────────────────────
        // O tópico não faz parte do payload — vem como parâmetro separado
        // no callback. É a "chave de roteamento" do MQTT.
        log.info("║  [TÓPICO]");
        log.info("║    Tópico     : {}", topic);
        log.info("║    Nível 1    : {}", extrairNivel(topic, 0));   // "pessoas"
        log.info("║    Nível 2    : {}", extrairNivel(topic, 1));   // "cadastro" | "atualizacao" | ...

        // ── PROPRIEDADES MQTT ────────────────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [PROPRIEDADES MQTT DA MENSAGEM]");

        // QoS efetivo — min(QoS_publicador, QoS_assinante)
        // Ex: publisher enviou QoS 2, subscriber assinou QoS 1 → entregue com QoS 1
        log.info("║    QoS        : {} — {}", message.getQos(), descricaoQos(message.getQos()));

        // retained=true → esta é a mensagem retida do tópico, publicada anteriormente.
        //                 Não foi publicada agora — o broker a guardou para novos subscribers.
        // retained=false → mensagem publicada em tempo real, não é um retain.
        log.info("║    Retained   : {} — {}", message.isRetained(),
                message.isRetained()
                        ? "mensagem retida — publicada antes desta sessão"
                        : "mensagem em tempo real");

        // duplicate=true → broker ou publisher estão reenviando (DUP flag no pacote MQTT).
        //                  Ocorre somente em QoS 1/2 quando o ACK não chegou a tempo.
        //                  O consumer deve ser idempotente para tratar duplicatas QoS 1.
        log.info("║    Duplicate  : {} — {}", message.isDuplicate(),
                message.isDuplicate()
                        ? "REENVIO — esta mensagem pode já ter sido processada"
                        : "primeira entrega");

        // Packet ID — identificador interno usado pelo protocolo para
        // correlacionar PUBACK (QoS 1), PUBREC/PUBREL/PUBCOMP (QoS 2).
        // QoS 0 não tem packet ID (valor 0).
        log.info("║    Packet ID  : {}", message.getId());

        // Tamanho do payload em bytes — MQTT é otimizado para payloads pequenos
        log.info("║    Payload    : {} bytes", message.getPayload().length);

        // ── PAYLOAD DESERIALIZADO ────────────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [PAYLOAD — conteúdo]");

        try {
            String payloadStr = new String(message.getPayload());

            // Tenta desserializar como PessoaDTO se for um evento de pessoa
            if (topic.startsWith("pessoas/") && !topic.contains("status")) {
                PessoaDTO pessoa = objectMapper.readValue(message.getPayload(), PessoaDTO.class);
                log.info("║    UUID       : {}", pessoa.uuid());
                log.info("║    Nome       : {}", pessoa.nome());
                log.info("║    Telefone   : {}", pessoa.telefone());
                log.info("║    Endereço   : {}", pessoa.endereco());
            } else {
                // Tópicos de status/will — exibe JSON bruto
                log.info("║    JSON       : {}", payloadStr);
            }
        } catch (Exception e) {
            log.warn("║    [payload não é JSON] raw={}", new String(message.getPayload()));
        }

        // ── COMPARATIVO COM AMQP ─────────────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [MQTT vs AMQP — o que falta nesta mensagem]");
        log.info("║    messageId       : ausente (MQTT não tem — use UUID no payload)");
        log.info("║    correlationId   : ausente (MQTT não tem)");
        log.info("║    headers         : ausentes (MQTT não tem — use payload estruturado)");
        log.info("║    timestamp       : ausente (MQTT não tem — use campo no payload)");
        log.info("║    contentType     : ausente (MQTT não tem — bytes puros)");
        log.info("║    deliveryMode    : não explícito (controlado por QoS + cleanSession)");
        log.info("╚══════════════════════════════════════════════════════════════════════");
    }

    private String descricaoQos(int qos) {
        return switch (qos) {
            case 0 -> "At Most Once — sem ACK, possível perda";
            case 1 -> "At Least Once — PUBACK, possível duplicata";
            case 2 -> "Exactly Once — 4 handshakes, sem duplicata";
            default -> "desconhecido";
        };
    }

    private String extrairNivel(String topic, int nivel) {
        String[] partes = topic.split("/");
        return nivel < partes.length ? partes[nivel] : "(nenhum)";
    }
}
