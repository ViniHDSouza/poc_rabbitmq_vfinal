package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.publisher.AmqpPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Cada endpoint demonstra um conjunto distinto de propriedades AMQP.
 * O objetivo é tornar cada propriedade observável nos logs de ambos os serviços.
 */
@RestController
@RequestMapping("/mensagens")
public class MensagemController {

    private final AmqpPublisher publisher;

    public MensagemController(AmqpPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * deliveryMode=1 (NON_PERSISTENT)
     * A mensagem não é gravada em disco. Perdida se o broker reiniciar.
     *
     * POST /mensagens/transiente
     */
    @PostMapping("/transiente")
    public ResponseEntity<Map<String, Object>> transiente(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarTransiente(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("cenario", "TRANSIENTE", "deliveryMode", 1,
                        "descricao", "Não persiste em disco — perdida se broker reiniciar",
                        "uuid", pessoa.uuid()));
    }

    /**
     * deliveryMode=2 (PERSISTENT)
     * A mensagem é gravada em disco. Sobrevive a reinicializações.
     *
     * POST /mensagens/persistente
     */
    @PostMapping("/persistente")
    public ResponseEntity<Map<String, Object>> persistente(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarPersistente(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("cenario", "PERSISTENTE", "deliveryMode", 2,
                        "descricao", "Persiste em disco — sobrevive a reinicializações",
                        "uuid", pessoa.uuid()));
    }

    /**
     * priority (0-9)
     * Mensagens com prioridade maior são entregues primeiro.
     *
     * POST /mensagens/prioridade?valor=8
     */
    @PostMapping("/prioridade")
    public ResponseEntity<Map<String, Object>> prioridade(
            @Valid @RequestBody PessoaDTO dto,
            @RequestParam(defaultValue = "5") @Min(0) @Max(9) int valor) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarComPrioridade(pessoa, valor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("cenario", "PRIORIDADE", "priority", valor,
                        "descricao", "Prioridade 0=menor, 9=maior",
                        "uuid", pessoa.uuid()));
    }

    /**
     * expiration (TTL por mensagem em ms)
     * A mensagem expira se não for consumida no prazo.
     *
     * POST /mensagens/expiracao?ttlMs=10000
     */
    @PostMapping("/expiracao")
    public ResponseEntity<Map<String, Object>> expiracao(
            @Valid @RequestBody PessoaDTO dto,
            @RequestParam(defaultValue = "10000") long ttlMs) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarComExpiracao(pessoa, ttlMs);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("cenario", "EXPIRACAO", "expirationMs", ttlMs,
                        "descricao", "Mensagem expira em " + ttlMs + "ms se não consumida",
                        "uuid", pessoa.uuid()));
    }

    /**
     * Todas as propriedades AMQP definidas simultaneamente.
     * O endpoint ideal para estudar o protocolo completo de uma vez.
     *
     * POST /mensagens/completa
     */
    @PostMapping("/completa")
    public ResponseEntity<Map<String, Object>> completa(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarCompleta(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("cenario", "COMPLETA",
                        "descricao", "Todas as propriedades AMQP definidas — observe os logs",
                        "uuid", pessoa.uuid()));
    }

    /**
     * Publica um de cada cenário sequencialmente.
     * Útil para comparar todos os cenários nos logs do consumer.
     *
     * POST /mensagens/todos-cenarios
     */
    @PostMapping("/todos-cenarios")
    public ResponseEntity<Map<String, Object>> todosCenarios() {
        PessoaDTO p1 = PessoaDTO.criar("Alice (Transiente)",  11911111111L, "Rua AMQP, 1");
        PessoaDTO p2 = PessoaDTO.criar("Bruno (Persistente)", 11922222222L, "Rua AMQP, 2");
        PessoaDTO p3 = PessoaDTO.criar("Carla (Prioridade)",  11933333333L, "Rua AMQP, 3");
        PessoaDTO p4 = PessoaDTO.criar("Diego (Expiração)",   11944444444L, "Rua AMQP, 4");
        PessoaDTO p5 = PessoaDTO.criar("Elena (Completa)",    11955555555L, "Rua AMQP, 5");

        publisher.publicarTransiente(p1);
        publisher.publicarPersistente(p2);
        publisher.publicarComPrioridade(p3, 9);
        publisher.publicarComExpiracao(p4, 30000);
        publisher.publicarCompleta(p5);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("mensagem", "5 mensagens publicadas — uma por cenário AMQP",
                        "cenarios", new String[]{"TRANSIENTE", "PERSISTENTE",
                                "PRIORIDADE=9", "EXPIRACAO=30s", "COMPLETA"}));
    }
}
