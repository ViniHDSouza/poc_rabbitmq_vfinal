package com.estudo.rabbitmq.subscriber.listener;

import com.estudo.rabbitmq.subscriber.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Subscriber 2 — Serviço de Auditoria
 *
 * Escuta a fila exclusiva pessoa.queue.auditoria.
 * Recebe as mesmas mensagens que o Subscriber de Email,
 * porém processa de forma completamente independente.
 *
 * Ponto-chave do Pub/Sub: se este subscriber estiver offline,
 * as mensagens ficam retidas na fila e são processadas quando
 * ele voltar — sem afetar o subscriber de Email.
 *
 * Cenário real: registrar log de auditoria de cada cadastro.
 */
@Component
public class SubscriberAuditoriaListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriberAuditoriaListener.class);

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @RabbitListener(queues = "${rabbitmq.queues.auditoria}")
    public void receberEvento(
            @Payload PessoaDTO pessoa,
            @Header("amqp_receivedExchange") String exchange,
            @Header("amqp_deliveryTag") long deliveryTag) {

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [SUBSCRIBER - AUDITORIA] Evento recebido");
        log.info("║  Exchange    : {}", exchange);
        log.info("║  Delivery Tag: {}", deliveryTag);
        log.info("║  UUID        : {}", pessoa.uuid());
        log.info("║  Nome        : {}", pessoa.nome());
        log.info("║  Telefone    : {}", pessoa.telefone());
        log.info("║  Endereço    : {}", pessoa.endereco());
        log.info("╚══════════════════════════════════════════════════");

        registrarAuditoria(pessoa, exchange, deliveryTag);
    }

    private void registrarAuditoria(PessoaDTO pessoa, String exchange, long deliveryTag) {
        String timestamp = LocalDateTime.now().format(FORMATTER);

        // Simula persistência de auditoria (ex: salvar no banco, Elasticsearch, etc.)
        log.info("[SUBSCRIBER - AUDITORIA] Registrando auditoria...");
        log.info("[SUBSCRIBER - AUDITORIA] timestamp={} | exchange={} | tag={} | uuid={} | nome={}",
                timestamp, exchange, deliveryTag, pessoa.uuid(), pessoa.nome());

        try {
            Thread.sleep(100); // simula latência da gravação
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[SUBSCRIBER - AUDITORIA] Auditoria registrada | uuid={}", pessoa.uuid());
    }
}
