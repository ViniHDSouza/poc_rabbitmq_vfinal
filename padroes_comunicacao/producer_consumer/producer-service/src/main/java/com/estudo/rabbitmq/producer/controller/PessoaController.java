package com.estudo.rabbitmq.producer.controller;

import com.estudo.rabbitmq.producer.config.RabbitMQConfig;
import com.estudo.rabbitmq.producer.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller do Producer.
 *
 * Recebe requisições HTTP e publica mensagens no RabbitMQ.
 * Retorna 202 Accepted porque o processamento real ocorre de forma assíncrona
 * no Consumer — o Producer apenas garante que a mensagem foi aceita pela fila.
 */
@RestController
@RequestMapping("/pessoas")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);

    private final RabbitTemplate rabbitTemplate;

    public PessoaController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica uma única pessoa na fila.
     *
     * POST /pessoas
     * Body: { "nome": "...", "telefone": 11999990000, "endereco": "..." }
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO publicar(@Valid @RequestBody PessoaDTO pessoaDTO) {
        // Gera UUID antes de publicar — o Consumer receberá o objeto completo
        var pessoa = new PessoaDTO(
                UUID.randomUUID(),
                pessoaDTO.nome(),
                pessoaDTO.telefone(),
                pessoaDTO.endereco()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY,
                pessoa
        );

        log.info("[PRODUCER] Pessoa publicada → id={} nome={}", pessoa.id(), pessoa.nome());
        return pessoa;
    }

    /**
     * Publica um lote de pessoas geradas automaticamente (útil para testes).
     *
     * POST /pessoas/lote?quantidade=N
     */
    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "5") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            var pessoa = new PessoaDTO(
                    UUID.randomUUID(),
                    "Pessoa Teste " + i,
                    11900000000L + i,
                    "Rua Teste, " + i
            );

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    pessoa
            );

            log.info("[PRODUCER] Lote [{}/{}] → id={} nome={}", i, quantidade, pessoa.id(), pessoa.nome());
        }

        return "Lote de %d pessoas publicado com sucesso.".formatted(quantidade);
    }
}
