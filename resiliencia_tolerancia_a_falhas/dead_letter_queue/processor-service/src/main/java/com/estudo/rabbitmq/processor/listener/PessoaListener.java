package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Listener da fila principal.
 *
 * Demonstra os dois caminhos do padrão DLQ:
 *
 * CAMINHO 1 — Sucesso:
 *   processarNegocio() retorna normalmente
 *   → basicAck(deliveryTag, false)
 *   → mensagem removida da fila permanentemente
 *
 * CAMINHO 2 — Falha (vai para DLQ):
 *   processarNegocio() lança BusinessException
 *   → basicNack(deliveryTag, false, requeue=FALSE)
 *   → broker encaminha para pessoa.dlx → pessoa.queue.dlq
 *
 * Por que requeue=false e não requeue=true?
 *   requeue=true  → mensagem volta ao início da fila → loop infinito de falha
 *   requeue=false → mensagem vai para a DLQ → analisada/reprocessada depois
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CHANNEL) Channel channel) throws IOException {

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [PROCESSOR] Mensagem recebida da fila principal");
        log.info("║  DeliveryTag  : {}", deliveryTag);
        log.info("║  UUID         : {}", pessoa.uuid());
        log.info("║  Nome         : {}", pessoa.nome());
        log.info("║  Telefone     : {}", pessoa.telefone());
        log.info("║  Endereço     : {}", pessoa.endereco());
        log.info("║  SimularFalha : {}", pessoa.simularFalha());
        log.info("╠══════════════════════════════════════════════════");

        try {
            processarNegocio(pessoa);

            // ── ACK ──────────────────────────────────────────────────
            // Processamento bem-sucedido.
            // multiple=false → confirma apenas este deliveryTag.
            // O broker remove a mensagem da fila permanentemente.
            channel.basicAck(deliveryTag, false);

            log.info("║ [PROCESSOR] ✔ ACK enviado — mensagem processada com sucesso");
            log.info("║  uuid={}", pessoa.uuid());

        } catch (BusinessException e) {

            log.error("║ [PROCESSOR] ✘ Falha de negócio detectada: {}", e.getMessage());

            // ── NACK com requeue=false ────────────────────────────────
            // Falha definitiva — não faz sentido tentar novamente.
            // requeue=false → o broker NÃO devolve à fila principal.
            // Como a fila foi declarada com x-dead-letter-exchange,
            // o broker encaminha automaticamente para pessoa.dlx → pessoa.queue.dlq.
            channel.basicNack(deliveryTag, false, false);

            log.warn("║ [PROCESSOR] ↪ NACK(requeue=false) enviado — mensagem encaminhada para DLQ");
            log.warn("║  uuid={}", pessoa.uuid());
        }

        log.info("╚══════════════════════════════════════════════════");
    }

    private void processarNegocio(PessoaDTO pessoa) throws BusinessException {
        log.info("║ [PROCESSOR] Executando lógica de negócio...");

        // Simula latência de processamento
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Se o campo simularFalha=true, lança exceção de negócio
        if (Boolean.TRUE.equals(pessoa.simularFalha())) {
            throw new BusinessException(
                    "Falha simulada de negócio para uuid=" + pessoa.uuid() +
                    " — mensagem será enviada para a DLQ");
        }

        log.info("║ [PROCESSOR] Negócio executado com sucesso | uuid={}", pessoa.uuid());
    }

    // Exceção de negócio — sinaliza falha definitiva (vai para DLQ)
    static class BusinessException extends Exception {
        public BusinessException(String message) {
            super(message);
        }
    }
}
