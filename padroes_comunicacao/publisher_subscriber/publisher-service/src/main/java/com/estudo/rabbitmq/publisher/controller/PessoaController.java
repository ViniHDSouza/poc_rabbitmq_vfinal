package com.estudo.rabbitmq.publisher.controller;

import com.estudo.rabbitmq.publisher.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/publicar")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public PessoaController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica uma Pessoa no FanoutExchange.
     *
     * A routing key é ignorada pelo Fanout — todos os subscribers
     * vinculados ao exchange receberão a mesma mensagem de forma
     * independente.
     *
     * POST /publicar
     * Body: { "nome": "Maria", "telefone": 11988887777, "endereco": "Av. Brasil, 100" }
     */
    @PostMapping
    public ResponseEntity<PessoaDTO> publicar(@Valid @RequestBody PessoaDTO pessoaDTO) {

        PessoaDTO pessoa = pessoaDTO.uuid() == null
                ? PessoaDTO.criar(pessoaDTO.nome(), pessoaDTO.telefone(), pessoaDTO.endereco())
                : pessoaDTO;

        log.info("[PUBLISHER] Publicando evento no fanout | uuid={} nome={}", pessoa.uuid(), pessoa.nome());

        // Fanout não usa routing key — passamos "" por convenção
        rabbitTemplate.convertAndSend(exchange, "", pessoa);

        log.info("[PUBLISHER] Evento publicado | exchange={} uuid={}", exchange, pessoa.uuid());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pessoa);
    }

    /**
     * Publica um lote para testar a entrega simultânea a todos os subscribers.
     *
     * POST /publicar/lote?quantidade=5
     */
    @PostMapping("/lote")
    public ResponseEntity<String> publicarLote(@RequestParam(defaultValue = "3") int quantidade) {

        for (int i = 1; i <= quantidade; i++) {
            PessoaDTO pessoa = PessoaDTO.criar(
                    "Pessoa " + i,
                    11900000000L + i,
                    "Rua Pub/Sub, " + i
            );
            rabbitTemplate.convertAndSend(exchange, "", pessoa);
            log.info("[PUBLISHER] Lote [{}/{}] | uuid={}", i, quantidade, pessoa.uuid());
        }

        return ResponseEntity.accepted()
                .body("%d evento(s) publicado(s) no fanout exchange.".formatted(quantidade));
    }
}
