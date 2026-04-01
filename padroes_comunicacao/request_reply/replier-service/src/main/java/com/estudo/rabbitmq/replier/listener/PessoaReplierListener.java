package com.estudo.rabbitmq.replier.listener;

import com.estudo.rabbitmq.replier.dto.PessoaRequestDTO;
import com.estudo.rabbitmq.replier.dto.PessoaResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class PessoaReplierListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaReplierListener.class);

    /**
     * Escuta a fila de requisições, processa e envia a resposta de volta.
     *
     * Mecanismo Request/Reply em detalhe:
     *
     * 1. O Requester define na mensagem:
     *    - correlationId : UUID único da requisição
     *    - replyTo       : "amq.rabbitmq.reply-to" (Direct Reply-To do broker)
     *
     * 2. O Spring AMQP lê automaticamente o header "replyTo" e direciona
     *    o retorno deste método para a fila correta.
     *    @SendTo("#{message.messageProperties.replyTo}") não é necessário
     *    quando se usa convertSendAndReceive — o framework já faz isso.
     *    Mas declaramos explicitamente via @Header para fins didáticos.
     *
     * 3. O Requester estava bloqueado em convertSendAndReceive() aguardando
     *    uma mensagem na reply queue com o mesmo correlationId.
     *    Ao receber, desbloqueia e retorna o PessoaResponseDTO.
     *
     * O correlationId é propagado automaticamente pelo Spring AMQP —
     * não precisamos manipulá-lo manualmente.
     */
    @RabbitListener(queues = "${rabbitmq.request-queue}")
    public PessoaResponseDTO processar(
            @Payload PessoaRequestDTO request,
            @Header("amqp_correlationId") String correlationId,
            @Header("amqp_replyTo") String replyTo) {

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [REPLIER] Requisição recebida");
        log.info("║  correlationId : {}", correlationId);
        log.info("║  replyTo       : {}", replyTo);
        log.info("║  UUID          : {}", request.uuid());
        log.info("║  Nome          : {}", request.nome());
        log.info("║  Telefone      : {}", request.telefone());
        log.info("║  Endereço      : {}", request.endereco());
        log.info("╠══════════════════════════════════════════════════");

        PessoaResponseDTO response = processarNegocio(request);

        log.info("║ [REPLIER] Enviando resposta → replyTo={}", replyTo);
        log.info("║  status    : {}", response.status());
        log.info("║  mensagem  : {}", response.mensagem());
        log.info("╚══════════════════════════════════════════════════");

        // O valor retornado aqui é enviado automaticamente pelo Spring AMQP
        // para a fila informada no header replyTo, com o mesmo correlationId.
        return response;
    }

    /**
     * Simula a lógica de negócio do Replier:
     * validação, enriquecimento de dados, consulta a banco, etc.
     */
    private PessoaResponseDTO processarNegocio(PessoaRequestDTO request) {

        log.info("[REPLIER] Processando regra de negócio para uuid={}", request.uuid());

        // Simula latência de processamento (ex: consulta ao banco)
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Validação de negócio — em produção: salvar, enriquecer, consultar, etc.
        boolean valido = request.nome() != null
                && !request.nome().isBlank()
                && request.telefone() != null
                && request.telefone() > 0;

        if (!valido) {
            return new PessoaResponseDTO(
                    request.uuid(),
                    request.nome(),
                    request.telefone(),
                    request.endereco(),
                    "REJEITADO",
                    "Dados inválidos: nome ou telefone incorretos",
                    "replier-service"
            );
        }

        return new PessoaResponseDTO(
                request.uuid(),
                request.nome(),
                request.telefone(),
                request.endereco(),
                "APROVADO",
                "Pessoa processada e validada com sucesso pelo replier-service",
                "replier-service"
        );
    }
}
