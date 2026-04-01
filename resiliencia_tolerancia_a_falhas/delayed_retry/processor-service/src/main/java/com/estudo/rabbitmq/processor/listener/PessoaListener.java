package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Listener da fila principal com delayed retry via broker.
 *
 * Ciclo de vida de uma mensagem com falha:
 *
 *   1ª tentativa: pessoa.queue → falha → NACK → pessoa.queue.wait (espera 5s)
 *                                                       ↓ TTL expira
 *   2ª tentativa: pessoa.queue → falha → NACK → pessoa.queue.wait (espera 5s)
 *                                                       ↓ TTL expira
 *   3ª tentativa: pessoa.queue → falha → parking-lot (fim — análise manual)
 *
 * O número da tentativa é rastreado pelo header x-death que o RabbitMQ
 * adiciona automaticamente a cada passagem pela wait queue.
 *
 * Diferença em relação ao Spring Retry (POC anterior):
 *   - Spring Retry: delay em Thread.sleep(), mensagem em memória
 *   - Delayed Retry: delay no broker (TTL), mensagem persistida em disco
 *   - Se o consumer cair durante o delay, o Spring Retry perde a mensagem;
 *     o Delayed Retry não perde porque ela está na wait queue do broker
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.max-retries}")   private int maxRetries;
    @Value("${rabbitmq.parking-lot}")   private String parkingLot;
    @Value("${processor.tipo-falha}")   private String tipoFalha;

    public PessoaListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CHANNEL) Channel channel,
            Message message) throws IOException {

        int tentativa = contarTentativas(message) + 1;

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [PROCESSOR] Tentativa {}/{} | tipo-falha={}", tentativa, maxRetries, tipoFalha);
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("║  Endereço : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════════════");

        try {
            executarNegocio(pessoa, tentativa);

            // Sucesso → ACK → broker remove da fila
            channel.basicAck(deliveryTag, false);
            log.info("║ [PROCESSOR] ✔ SUCESSO na tentativa {}/{}", tentativa, maxRetries);
            log.info("║             → ACK enviado — mensagem removida definitivamente");

        } catch (Exception e) {

            if (tentativa >= maxRetries) {
                // Retries esgotados → envia para parking-lot (fila morta definitiva)
                log.error("║ [PROCESSOR] ✘ RETRIES ESGOTADOS após {}/{} tentativas", tentativa, maxRetries);
                log.error("║             → Enviando para parking-lot: {}", parkingLot);

                rabbitTemplate.convertAndSend("", parkingLot, pessoa);
                channel.basicAck(deliveryTag, false);  // ACK para remover da fila principal

            } else {
                // Ainda tem retries → NACK(requeue=false) → vai para wait queue (TTL)
                log.warn("║ [PROCESSOR] ✘ Falha na tentativa {}/{}: {}", tentativa, maxRetries, e.getMessage());
                log.warn("║             → NACK(requeue=false) → wait queue (delay de TTL)");
                log.warn("║             → Após TTL expirar, broker devolverá para fila principal");

                channel.basicNack(deliveryTag, false, false);
            }
        }

        log.info("╚══════════════════════════════════════════════════════════");
    }

    /**
     * Conta quantas vezes a mensagem já passou pela wait queue,
     * usando o header x-death que o RabbitMQ adiciona automaticamente.
     *
     * Cada vez que uma mensagem é dead-lettered (NACK + requeue=false),
     * o broker adiciona/incrementa uma entrada no array x-death com:
     *   - queue: nome da fila de onde veio
     *   - reason: "rejected" | "expired" | "maxlen"
     *   - count: quantas vezes morreu nessa fila por esse motivo
     *
     * Somamos os counts de todas as entradas com reason="expired"
     * (que é o motivo quando a mensagem sai da wait queue após o TTL).
     */
    @SuppressWarnings("unchecked")
    private int contarTentativas(Message message) {
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        if (xDeath == null) return 0;

        if (xDeath instanceof List<?> lista) {
            int total = 0;
            for (Object entry : lista) {
                if (entry instanceof Map<?, ?> mapa) {
                    Object count = mapa.get("count");
                    if (count instanceof Number n) {
                        total += n.intValue();
                    }
                }
            }
            return total;
        }

        return 0;
    }

    private void executarNegocio(PessoaDTO pessoa, int tentativa) {
        switch (tipoFalha.toUpperCase()) {
            case "SUCESSO" -> {
                log.info("║  Processando normalmente...");
            }
            case "TRANSIENTE" -> {
                if (tentativa < 3) {
                    throw new RuntimeException("Falha transitória simulada — recurso externo instável");
                }
                log.info("║  Recurso externo recuperou — processando com sucesso!");
            }
            case "PERMANENTE" -> {
                throw new RuntimeException("Falha permanente simulada — dado inválido");
            }
            default -> log.info("║  Processando normalmente...");
        }
    }
}
