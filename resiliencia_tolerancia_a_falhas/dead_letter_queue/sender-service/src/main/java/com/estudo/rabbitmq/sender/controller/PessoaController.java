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
     * Publica uma pessoa válida — será processada com sucesso pelo Processor.
     * O Processor envia basicAck e a mensagem é removida da fila.
     *
     * POST /pessoas
     */
    @PostMapping
    public ResponseEntity<PessoaDTO> publicar(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco())
                : dto;

        log.info("[SENDER] Publicando pessoa VÁLIDA | uuid={} nome={}", pessoa.uuid(), pessoa.nome());
        rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
        log.info("[SENDER] Mensagem enfileirada | uuid={}", pessoa.uuid());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pessoa);
    }

    /**
     * Publica uma pessoa marcada para falhar — o Processor simulará
     * um erro de negócio e enviará basicNack(requeue=false).
     * O broker encaminhará a mensagem para a DLQ.
     *
     * POST /pessoas/falha
     */
    @PostMapping("/falha")
    public ResponseEntity<PessoaDTO> publicarComFalha(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = PessoaDTO.criarComFalha(dto.nome(), dto.telefone(), dto.endereco());

        log.info("[SENDER] Publicando pessoa com FALHA SIMULADA | uuid={} nome={}", pessoa.uuid(), pessoa.nome());
        rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
        log.info("[SENDER] Mensagem (com falha) enfileirada | uuid={}", pessoa.uuid());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pessoa);
    }

    /**
     * Publica um lote misto: metade válidas, metade com falha simulada.
     * Ideal para observar no painel do RabbitMQ a divisão entre
     * pessoa.queue e pessoa.queue.dlq.
     *
     * POST /pessoas/lote?quantidade=6
     */
    @PostMapping("/lote")
    public ResponseEntity<String> publicarLote(@RequestParam(defaultValue = "6") int quantidade) {
        int falhas = 0;
        int sucessos = 0;

        for (int i = 1; i <= quantidade; i++) {
            boolean comFalha = (i % 2 == 0); // pares vão para DLQ, ímpares são processados

            PessoaDTO pessoa = comFalha
                    ? PessoaDTO.criarComFalha("Pessoa-" + i, 11900000000L + i, "Rua DLQ, " + i)
                    : PessoaDTO.criar("Pessoa-" + i, 11900000000L + i, "Rua DLQ, " + i);

            rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);

            if (comFalha) falhas++;
            else sucessos++;

            log.info("[SENDER] [{}/{}] uuid={} | simularFalha={}", i, quantidade, pessoa.uuid(), comFalha);
        }

        return ResponseEntity.accepted().body(
                "%d mensagens enviadas — %d esperadas na fila principal (sucesso) | %d esperadas na DLQ (falha)"
                        .formatted(quantidade, sucessos, falhas));
    }
}
