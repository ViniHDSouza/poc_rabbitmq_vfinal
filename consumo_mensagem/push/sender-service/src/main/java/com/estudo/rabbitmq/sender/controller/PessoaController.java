package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pessoas")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    public PessoaController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica uma única pessoa.
     * No modo PUSH, o consumer receberá esta mensagem automaticamente
     * assim que o broker a processar — sem nenhuma ação do consumer.
     *
     * POST /pessoas
     */
    @PostMapping
    public ResponseEntity<PessoaDTO> publicar(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco())
                : dto;

        rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
        log.info("[SENDER] Enfileirada | uuid={} nome={}", pessoa.uuid(), pessoa.nome());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pessoa);
    }

    /**
     * Publica N pessoas de uma vez.
     * O consumer-service receberá cada mensagem automaticamente via PUSH,
     * respeitando o prefetch configurado.
     *
     * POST /pessoas/lote?quantidade=10
     */
    @PostMapping("/lote")
    public ResponseEntity<String> publicarLote(@RequestParam(defaultValue = "10") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            PessoaDTO pessoa = PessoaDTO.criar(
                    "Pessoa-" + i,
                    11900000000L + i,
                    "Rua Push, " + i
            );
            rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
            log.info("[SENDER] [{}/{}] enfileirada | uuid={}", i, quantidade, pessoa.uuid());
        }
        return ResponseEntity.accepted()
                .body("%d pessoa(s) enfileirada(s). O consumer-service as receberá via PUSH automaticamente."
                        .formatted(quantidade));
    }
}
