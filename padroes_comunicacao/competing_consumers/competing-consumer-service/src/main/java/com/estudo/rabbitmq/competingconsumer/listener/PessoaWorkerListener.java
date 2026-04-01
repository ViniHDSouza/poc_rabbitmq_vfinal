package com.estudo.rabbitmq.competingconsumer.listener;

import com.estudo.rabbitmq.competingconsumer.config.WorkerMetrics;
import com.estudo.rabbitmq.competingconsumer.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Pool de workers que competem pela mesma fila.
 *
 * Como o Competing Consumers funciona aqui:
 *
 * O Spring cria N threads (concurrency=3..5 no yml).
 * Cada thread é um worker independente registrado no broker.
 * O RabbitMQ distribui as mensagens entre os workers disponíveis.
 *
 * Com prefetch=1:
 *   - O broker entrega apenas 1 mensagem por vez a cada worker
 *   - O próximo envio acontece somente após o basicAck
 *   - Workers lentos não acumulam mensagens; workers rápidos
 *     pegam mais trabalho → Fair Dispatch
 *
 * Com acknowledge-mode=manual:
 *   - basicAck  → mensagem removida da fila (processamento OK)
 *   - basicNack → mensagem devolvida à fila (requeue=true) ou
 *                 descartada/DLQ (requeue=false)
 */
@Component
public class PessoaWorkerListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaWorkerListener.class);

    private final WorkerMetrics metrics;

    @Value("${workers.processing-time-ms:800}")
    private long processingTimeMs;

    public PessoaWorkerListener(WorkerMetrics metrics) {
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CHANNEL) Channel channel) throws IOException {

        // Identifica o worker pela thread atual
        String workerName = Thread.currentThread().getName();

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [WORKER] Mensagem recebida");
        log.info("║  Worker      : {}", workerName);
        log.info("║  DeliveryTag : {}", deliveryTag);
        log.info("║  UUID        : {}", pessoa.uuid());
        log.info("║  Nome        : {}", pessoa.nome());
        log.info("║  Endereço    : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════");

        try {
            processarNegocio(pessoa, workerName);

            // ── ACK manual ──────────────────────────────────────────
            // Confirma que esta mensagem foi processada com sucesso.
            // O broker a remove da fila permanentemente.
            // multiple=false: confirma apenas este deliveryTag, não os anteriores.
            channel.basicAck(deliveryTag, false);

            metrics.registrarSucesso(workerName);

            log.info("║ [WORKER] ACK enviado | worker={} uuid={}", workerName, pessoa.uuid());

        } catch (Exception e) {

            log.error("║ [WORKER] Falha no processamento | worker={} uuid={} erro={}",
                    workerName, pessoa.uuid(), e.getMessage());

            // ── NACK manual ─────────────────────────────────────────
            // requeue=false → não recoloca na fila (evita loop infinito).
            // Se houver DLQ configurada, a mensagem vai para lá.
            // requeue=true  → devolve ao início da fila (cuidado: loop).
            channel.basicNack(deliveryTag, false, false);

            metrics.registrarFalha(workerName);
        }

        log.info("║  Total processado por este worker: {}",
                metrics.getSucessosPorWorker().getOrDefault(workerName, 0L));
        log.info("╚══════════════════════════════════════════════════");
    }

    /**
     * Simula processamento com latência variável por worker.
     *
     * A latência intencional é essencial para demonstrar o Fair Dispatch:
     * envie 12 mensagens e observe nos logs como os workers mais rápidos
     * (threads que terminam antes) pegam mais mensagens.
     *
     * Experimento: altere workers.processing-time-ms no yml para
     * valores diferentes e observe a redistribuição.
     */
    private void processarNegocio(PessoaDTO pessoa, String workerName) throws InterruptedException {

        log.info("║ [WORKER] Processando | worker={} uuid={}", workerName, pessoa.uuid());

        // Latência simulada — em produção: salvar em banco, chamar API, etc.
        Thread.sleep(processingTimeMs);

        if (pessoa.nome() == null || pessoa.nome().isBlank()) {
            throw new IllegalArgumentException("Nome inválido para uuid=" + pessoa.uuid());
        }

        log.info("║ [WORKER] Processado com sucesso | worker={} uuid={}", workerName, pessoa.uuid());
    }
}
