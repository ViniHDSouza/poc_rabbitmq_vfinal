package com.estudo.rabbitmq.subscriber.listener;

import com.estudo.rabbitmq.subscriber.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Subscriber 1 — Serviço de E-mail
 *
 * Escuta a fila exclusiva pessoa.queue.email.
 * Recebe TODAS as mensagens publicadas no FanoutExchange,
 * de forma totalmente independente do Subscriber de Auditoria.
 *
 * Cenário real: enviar e-mail de boas-vindas ao cadastrar uma pessoa.
 */
@Component
public class SubscriberEmailListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriberEmailListener.class);

    @RabbitListener(queues = "${rabbitmq.queues.email}")
    public void receberEvento(@Payload PessoaDTO pessoa) {

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [SUBSCRIBER - EMAIL] Evento recebido");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("║  Endereço : {}", pessoa.endereco());
        log.info("╚══════════════════════════════════════════════════");

        enviarEmailBoasVindas(pessoa);
    }

    private void enviarEmailBoasVindas(PessoaDTO pessoa) {
        // Simula chamada ao serviço de e-mail (ex: SendGrid, JavaMailSender)
        log.info("[SUBSCRIBER - EMAIL] Simulando envio de e-mail de boas-vindas para: {}", pessoa.nome());

        try {
            Thread.sleep(150); // simula latência do envio
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[SUBSCRIBER - EMAIL] E-mail enviado com sucesso | uuid={}", pessoa.uuid());
    }
}
