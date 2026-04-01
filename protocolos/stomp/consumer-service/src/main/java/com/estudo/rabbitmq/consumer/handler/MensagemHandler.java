package com.estudo.rabbitmq.consumer.handler;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;

/**
 * Handler STOMP que inspeciona e loga TODOS os headers do frame MESSAGE.
 *
 * Um frame MESSAGE (entregue pelo broker ao subscriber) tem esta estrutura:
 *
 *   MESSAGE
 *   destination:/queue/pessoa.queue
 *   message-id:T_001-1
 *   subscription:sub-0
 *   content-type:application/json
 *   content-length:112
 *   ack:T_001-1                    ← presente quando ack=client ou client-individual
 *   x-custom-header:valor
 *
 *   {"uuid":"...","nome":"Ana",...}^@
 *
 * Headers do frame MESSAGE (nível STOMP):
 *   destination   → destino onde o publisher enviou
 *   message-id    → ID único desta entrega (gerado pelo broker)
 *   subscription  → ID da assinatura que recebe esta mensagem
 *   content-type  → MIME type do body
 *   content-length→ tamanho do body em bytes
 *   ack           → ID a usar no frame ACK (presente se ack≠auto)
 *
 * Headers adicionados pelo RabbitMQ STOMP plugin:
 *   redelivered   → true se a mensagem foi reenfileirada
 *   exchange      → exchange de onde a mensagem veio
 *   routing-keys  → routing keys usadas
 *   delivery-mode → 1=transiente, 2=persistente
 *   priority      → prioridade AMQP
 */
@Component
public class MensagemHandler implements StompFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(MensagemHandler.class);

    private final ObjectMapper objectMapper;
    private StompSession stompSession;

    public MensagemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setStompSession(StompSession stompSession) {
        this.stompSession = stompSession;
    }

    /**
     * Informa ao Spring o tipo Java para deserializar o payload.
     * Usamos byte[] para receber o payload bruto e controlar a deserialização.
     */
    @Override
    public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
    }

    /**
     * Invocado para cada frame MESSAGE recebido do broker.
     * Inspeciona todos os headers e o payload.
     */
    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [STOMP] Frame MESSAGE recebido do broker");
        log.info("╠══════════════════════════════════════════════════════════════════════");

        // ── CABEÇALHO DO FRAME STOMP ─────────────────────────────────────────
        log.info("║  [FRAME STOMP — headers padrão do protocolo]");
        log.info("║    MESSAGE");

        // destination: onde o publisher enviou a mensagem
        log.info("║    destination:{}", headers.getDestination());

        // message-id: identificador único desta entrega, gerado pelo broker
        // Formato RabbitMQ: "T_001-N" onde N é um contador sequencial da sessão
        log.info("║    message-id:{}", headers.getMessageId());

        // subscription: ID da assinatura que corresponde a esta entrega
        // É o mesmo ID informado no frame SUBSCRIBE (gerado pelo client Spring)
        log.info("║    subscription:{}", headers.getSubscription());

        // content-type: MIME type do payload
        log.info("║    content-type:{}", headers.getContentType());

        // content-length: tamanho do payload em bytes
        log.info("║    content-length:{}", headers.getContentLength());

        // ack: ID a ser referenciado no frame ACK/NACK
        // Presente apenas quando o modo de ACK é "client" ou "client-individual"
        String ackId = headers.getAck();
        log.info("║    ack:{} — {}",
                ackId != null ? ackId : "(ausente)",
                ackId != null ? "ACK manual necessário" : "ACK automático (ack=auto)");

        // receipt-id: presente se o frame SEND tinha header "receipt"
        // O broker inclui aqui o mesmo receipt-id do SEND
        String receiptId = headers.getFirst("receipt-id");
        if (receiptId != null) {
            log.info("║    receipt-id:{} ← confirma o SEND que tinha receipt:{}", receiptId, receiptId);
        }

        // ── HEADERS ADICIONADOS PELO RABBITMQ STOMP PLUGIN ──────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [HEADERS DO RABBITMQ STOMP PLUGIN — metadados AMQP expostos]");

        // redelivered: true se a mensagem foi devolvida à fila (NACK/falha anterior)
        String redelivered = headers.getFirst("redelivered");
        log.info("║    redelivered:{} — {}",
                redelivered != null ? redelivered : "false",
                "true = mensagem já foi entregue antes");

        // exchange: exchange AMQP de onde a mensagem veio
        log.info("║    exchange:{}", headers.getFirst("exchange"));

        // routing-keys: routing keys usadas no envio AMQP
        log.info("║    routing-keys:{}", headers.getFirst("routing-keys"));

        // delivery-mode: durabilidade AMQP (1=transiente, 2=persistente)
        String deliveryMode = headers.getFirst("delivery-mode");
        log.info("║    delivery-mode:{} — {}", deliveryMode,
                "2".equals(deliveryMode) ? "PERSISTENT" : "NON_PERSISTENT");

        // priority: prioridade AMQP (0-9)
        log.info("║    priority:{}", headers.getFirst("priority"));

        // ── HEADERS CUSTOMIZADOS (se houver) ────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [HEADERS CUSTOMIZADOS]");
        headers.toSingleValueMap().forEach((k, v) -> {
            if (k.startsWith("x-")) {
                log.info("║    {}:{}", k, v);
            }
        });

        // ── PAYLOAD ─────────────────────────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [PAYLOAD — body do frame]");

        byte[] rawBytes = (byte[]) payload;
        String rawPayload = new String(rawBytes);

        try {
            PessoaDTO pessoa = objectMapper.readValue(rawBytes, PessoaDTO.class);
            log.info("║    UUID      : {}", pessoa.uuid());
            log.info("║    Nome      : {}", pessoa.nome());
            log.info("║    Telefone  : {}", pessoa.telefone());
            log.info("║    Endereço  : {}", pessoa.endereco());
        } catch (Exception e) {
            log.warn("║    [payload não é PessoaDTO] raw={}", rawPayload);
        }

        // ── ACK MANUAL ──────────────────────────────────────────────────────
        // Quando ack=client-individual, o consumer DEVE enviar um frame ACK
        // com o ack-id recebido no MESSAGE. Sem ACK, o broker considera a
        // mensagem como não confirmada e pode reenviá-la ao reconectar.
        //
        // Frame ACK enviado pelo consumer:
        //   ACK
        //   id:T_001-1     ← mesmo ack-id do MESSAGE
        //   [linha em branco]^@
        if (ackId != null && stompSession != null && stompSession.isConnected()) {
            stompSession.acknowledge(ackId, true);
            log.info("╠══════════════════════════════════════════════════════════════════════");
            log.info("║  [ACK] Frame ACK enviado");
            log.info("║    ACK");
            log.info("║    id:{}", ackId);
            log.info("║    [linha em branco]^@");
        }

        log.info("╚══════════════════════════════════════════════════════════════════════");
    }
}
