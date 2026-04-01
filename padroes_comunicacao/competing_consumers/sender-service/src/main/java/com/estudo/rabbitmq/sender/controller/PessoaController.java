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
@RequestMapping("/enviar")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.queue}")
    private String queue;

    public PessoaController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Envia uma única Pessoa para a fila.
     * Algum dos workers disponíveis irá processá-la.
     *
     * POST /enviar
     */
    @PostMapping
    public ResponseEntity<PessoaDTO> enviar(@Valid @RequestBody PessoaDTO pessoaDTO) {

        PessoaDTO pessoa = pessoaDTO.uuid() == null
                ? PessoaDTO.criar(pessoaDTO.nome(), pessoaDTO.telefone(), pessoaDTO.endereco())
                : pessoaDTO;

        log.info("[SENDER] Enviando pessoa para a fila | uuid={} nome={}", pessoa.uuid(), pessoa.nome());

        // Default Exchange: routing key = nome da fila
        rabbitTemplate.convertAndSend("", queue, pessoa);

        log.info("[SENDER] Mensagem enfileirada | fila={} uuid={}", queue, pessoa.uuid());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pessoa);
    }

    /**
     * Envia um lote — ideal para visualizar a distribuição entre workers.
     * Com 3 workers e prefetch=1, as mensagens são distribuídas
     * aproximadamente em round-robin entre os workers disponíveis.
     *
     * POST /enviar/lote?quantidade=12
     */
    @PostMapping("/lote")
    public ResponseEntity<String> enviarLote(@RequestParam(defaultValue = "12") int quantidade) {

        log.info("[SENDER] Enviando lote de {} mensagens → fila={}", quantidade, queue);

        long inicio = System.currentTimeMillis();

        for (int i = 1; i <= quantidade; i++) {
            PessoaDTO pessoa = PessoaDTO.criar(
                    "Pessoa-" + i,
                    11900000000L + i,
                    "Rua Competing Consumers, " + i
            );
            rabbitTemplate.convertAndSend("", queue, pessoa);
            log.info("[SENDER] [{}/{}] enfileirada | uuid={}", i, quantidade, pessoa.uuid());
        }

        long duracao = System.currentTimeMillis() - inicio;
        String resposta = "%d mensagens enfileiradas em %dms. Observe nos logs dos workers como são distribuídas."
                .formatted(quantidade, duracao);

        log.info("[SENDER] Lote completo — {}", resposta);
        return ResponseEntity.accepted().body(resposta);
    }
}
