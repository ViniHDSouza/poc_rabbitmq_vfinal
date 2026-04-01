package com.estudo.rabbitmq.sender.publisher;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publica mensagens MQTT demonstrando as propriedades do protocolo.
 *
 * Uma mensagem MQTT tem apenas três propriedades configuráveis pelo publisher:
 *   - QoS (Quality of Service): 0, 1 ou 2
 *   - Retained: se o broker deve guardar a última mensagem do tópico
 *   - Payload: array de bytes (sem tipo, sem headers — ao contrário do AMQP)
 *
 * O tópico é informado na publicação (não faz parte do payload).
 */
@Component
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    @Value("${mqtt.topics.cadastro}")
    private String topicCadastro;

    @Value("${mqtt.topics.atualizacao}")
    private String topicAtualizacao;

    @Value("${mqtt.topics.exclusao}")
    private String topicExclusao;

    public MqttPublisher(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------------
    // QoS 0 — At Most Once (fire-and-forget)
    //
    // O publisher envia a mensagem uma única vez e não verifica se chegou.
    // Não há ACK, não há retransmissão.
    //
    // Fluxo MQTT:
    //   Publisher → PUBLISH(QoS=0) → Broker  [fim — sem confirmação]
    //
    // Quando usar: dados de sensor com alta frequência onde a perda
    // ocasional é aceitável (ex: temperatura a cada segundo).
    // ----------------------------------------------------------------
    public void publicarQos0(PessoaDTO pessoa) throws MqttException {
        MqttMessage message = construirMensagem(pessoa, 0, false);
        logarPublicacao(topicCadastro, pessoa, 0, false, "At Most Once — fire-and-forget, sem ACK");
        mqttClient.publish(topicCadastro, message);
    }

    // ----------------------------------------------------------------
    // QoS 1 — At Least Once
    //
    // O broker confirma o recebimento com PUBACK. Se o publisher não
    // receber PUBACK dentro do timeout, reenvia com DUP=1 (duplicate).
    // A mensagem pode chegar mais de uma vez — o consumer deve ser
    // idempotente.
    //
    // Fluxo MQTT:
    //   Publisher → PUBLISH(QoS=1, packetId=X) → Broker
    //   Broker    → PUBACK(packetId=X)          → Publisher
    //   [se PUBACK não chegar → Publisher reenvia com DUP=1]
    //
    // Quando usar: comandos, eventos onde a entrega é obrigatória
    // e o consumer consegue tratar duplicatas.
    // ----------------------------------------------------------------
    public void publicarQos1(PessoaDTO pessoa) throws MqttException {
        MqttMessage message = construirMensagem(pessoa, 1, false);
        logarPublicacao(topicCadastro, pessoa, 1, false, "At Least Once — broker confirma com PUBACK, possível duplicata");
        mqttClient.publish(topicCadastro, message);
    }

    // ----------------------------------------------------------------
    // QoS 2 — Exactly Once
    //
    // Entrega garantida e sem duplicatas. Usa um handshake de 4 passos
    // (PUBLISH → PUBREC → PUBREL → PUBCOMP).
    //
    // Fluxo MQTT:
    //   Publisher → PUBLISH(QoS=2, packetId=X) → Broker
    //   Broker    → PUBREC(packetId=X)          → Publisher
    //   Publisher → PUBREL(packetId=X)          → Broker
    //   Broker    → PUBCOMP(packetId=X)         → Publisher
    //
    // Quando usar: transações financeiras, pedidos, eventos críticos
    // onde duplicata causaria problema real.
    // Custo: 4 pacotes de rede por mensagem (vs 1 no QoS 0).
    // ----------------------------------------------------------------
    public void publicarQos2(PessoaDTO pessoa) throws MqttException {
        MqttMessage message = construirMensagem(pessoa, 2, false);
        logarPublicacao(topicCadastro, pessoa, 2, false, "Exactly Once — handshake 4 passos, sem duplicatas");
        mqttClient.publish(topicCadastro, message);
    }

    // ----------------------------------------------------------------
    // RETAIN = true
    //
    // O broker armazena a ÚLTIMA mensagem retida de cada tópico.
    // Quando um novo subscriber assina o tópico, recebe imediatamente
    // essa mensagem retida — mesmo que nenhuma nova mensagem tenha
    // chegado.
    //
    // Comportamento:
    //   - Cada tópico guarda no máximo UMA mensagem retida
    //   - Publicar retain=false sobrescreve sem alterar a retida
    //   - Para apagar a mensagem retida: publicar payload vazio (0 bytes) com retain=true
    //
    // Quando usar: estado atual de dispositivos IoT, última leitura
    // de sensor, configuração que subscribers precisam ao conectar.
    // ----------------------------------------------------------------
    public void publicarRetido(PessoaDTO pessoa) throws MqttException {
        MqttMessage message = construirMensagem(pessoa, 1, true);
        logarPublicacao(topicAtualizacao, pessoa, 1, true,
                "Broker guarda esta mensagem — novos subscribers a receberão imediatamente");
        mqttClient.publish(topicAtualizacao, message);
    }

    // ----------------------------------------------------------------
    // Limpar mensagem retida de um tópico
    //
    // Publica payload vazio (0 bytes) com retain=true.
    // O broker apaga a mensagem retida do tópico.
    // ----------------------------------------------------------------
    public void limparRetido(String topico) throws MqttException {
        MqttMessage mensagemVazia = new MqttMessage(new byte[0]);
        mensagemVazia.setQos(1);
        mensagemVazia.setRetained(true);
        log.info("[MQTT] Limpando mensagem retida | tópico={}", topico);
        mqttClient.publish(topico, mensagemVazia);
        log.info("[MQTT] Mensagem retida removida do broker | tópico={}", topico);
    }

    // ----------------------------------------------------------------
    // Publica em tópico de exclusão com QoS 1
    // ----------------------------------------------------------------
    public void publicarExclusao(PessoaDTO pessoa) throws MqttException {
        MqttMessage message = construirMensagem(pessoa, 1, false);
        logarPublicacao(topicExclusao, pessoa, 1, false, "Evento de exclusão");
        mqttClient.publish(topicExclusao, message);
    }

    // ----------------------------------------------------------------
    // Publica um de cada cenário — útil para comparação nos logs
    // ----------------------------------------------------------------
    public void publicarTodosCenarios(
            PessoaDTO p0, PessoaDTO p1, PessoaDTO p2, PessoaDTO pRetido) throws MqttException {
        publicarQos0(p0);
        publicarQos1(p1);
        publicarQos2(p2);
        publicarRetido(pRetido);
    }

    // ── Utilitários ─────────────────────────────────────────────────

    private MqttMessage construirMensagem(PessoaDTO pessoa, int qos, boolean retained) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(pessoa);
            MqttMessage message = new MqttMessage(payload);
            message.setQos(qos);
            message.setRetained(retained);
            return message;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao serializar PessoaDTO", e);
        }
    }

    private void logarPublicacao(String topico, PessoaDTO pessoa,
                                  int qos, boolean retained, String descricaoQos) {
        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [MQTT PUBLISH]");
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  Tópico       : {}", topico);
        log.info("║  QoS          : {} — {}", qos, descricaoQos);
        log.info("║  Retained     : {}", retained);
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  UUID         : {}", pessoa.uuid());
        log.info("║  Nome         : {}", pessoa.nome());
        log.info("╚══════════════════════════════════════════════════════════════════════");
    }
}
