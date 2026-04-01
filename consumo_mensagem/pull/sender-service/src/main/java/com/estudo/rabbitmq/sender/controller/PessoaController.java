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
     * Publica uma única pessoa na fila.
     * Ela ficará retida lá até que alguém faça PULL.
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
     * Útil para acumular mensagens na fila antes de testá-las no modo PULL.
     *
     * POST /pessoas/lote?quantidade=10
     */
    @PostMapping("/lote")
    public ResponseEntity<String> publicarLote(@RequestParam(defaultValue = "10") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            PessoaDTO pessoa = PessoaDTO.criar(
                    "Pessoa-" + i,
                    11900000000L + i,
                    "Rua Pull, " + i
            );
            rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
            log.info("[SENDER] [{}/{}] enfileirada | uuid={}", i, quantidade, pessoa.uuid());
        }
        return ResponseEntity.accepted()
                .body("%d pessoa(s) enfileirada(s). Fila pronta para PULL."
                        .formatted(quantidade));
    }
}
