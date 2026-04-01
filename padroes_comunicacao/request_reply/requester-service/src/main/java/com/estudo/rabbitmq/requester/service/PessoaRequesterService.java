package com.estudo.rabbitmq.requester.service;

import com.estudo.rabbitmq.requester.dto.PessoaRequestDTO;
import com.estudo.rabbitmq.requester.dto.PessoaResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PessoaRequesterService {

    private static final Logger log = LoggerFactory.getLogger(PessoaRequesterService.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.request-queue}")
    private String requestQueue;

    public PessoaRequesterService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Envia uma PessoaRequestDTO para a fila de requisição e AGUARDA a resposta.
     *
     * convertSendAndReceive() gerencia internamente:
     *   - correlationId  → identifica unicamente esta requisição
     *   - replyTo        → aponta para amq.rabbitmq.reply-to (Direct Reply-To)
     *   - bloqueio       → segura a thread até receber a resposta ou estourar o timeout
     *
     * @return PessoaResponseDTO com os dados enriquecidos pelo Replier
     * @throws IllegalStateException se o Replier não responder dentro do timeout
     */
    public PessoaResponseDTO enviarRequisicao(PessoaRequestDTO request) {

        log.info("[REQUESTER] Enviando requisição | uuid={} nome={}", request.uuid(), request.nome());
        log.info("[REQUESTER] Aguardando resposta na reply queue...");

        try {
            // Publica na fila e bloqueia aguardando resposta
            // O exchange "" (default) roteia diretamente pela request-queue
            PessoaResponseDTO response = (PessoaResponseDTO)
                    rabbitTemplate.convertSendAndReceive("", requestQueue, request);

            if (response == null) {
                throw new IllegalStateException(
                        "Timeout: Replier não respondeu dentro do prazo para uuid=" + request.uuid());
            }

            log.info("[REQUESTER] Resposta recebida | uuid={} status={} mensagem={}",
                    response.uuid(), response.status(), response.mensagem());

            return response;

        } catch (AmqpException e) {
            log.error("[REQUESTER] Erro de comunicação com o broker | uuid={}", request.uuid(), e);
            throw new RuntimeException("Falha na comunicação com o RabbitMQ: " + e.getMessage(), e);
        }
    }
}
