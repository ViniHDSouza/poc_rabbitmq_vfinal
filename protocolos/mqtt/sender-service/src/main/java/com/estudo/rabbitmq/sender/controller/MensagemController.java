package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.publisher.MqttPublisher;
import jakarta.validation.Valid;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mensagens")
public class MensagemController {

    private final MqttPublisher publisher;

    public MensagemController(MqttPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * QoS 0 — At Most Once (fire-and-forget).
     * Sem confirmação. A mensagem pode ser perdida.
     *
     * POST /mensagens/qos0
     */
    @PostMapping("/qos0")
    public ResponseEntity<Map<String, Object>> qos0(@Valid @RequestBody PessoaDTO dto) throws MqttException {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarQos0(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "qos", 0,
                "descricao", "At Most Once — fire-and-forget, sem ACK, possível perda",
                "uuid", pessoa.uuid().toString()));
    }

    /**
     * QoS 1 — At Least Once.
     * Broker confirma com PUBACK. Possível duplicata.
     *
     * POST /mensagens/qos1
     */
    @PostMapping("/qos1")
    public ResponseEntity<Map<String, Object>> qos1(@Valid @RequestBody PessoaDTO dto) throws MqttException {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarQos1(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "qos", 1,
                "descricao", "At Least Once — PUBACK, possível duplicata",
                "uuid", pessoa.uuid().toString()));
    }

    /**
     * QoS 2 — Exactly Once.
     * Handshake de 4 passos. Entrega garantida sem duplicatas.
     *
     * POST /mensagens/qos2
     */
    @PostMapping("/qos2")
    public ResponseEntity<Map<String, Object>> qos2(@Valid @RequestBody PessoaDTO dto) throws MqttException {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarQos2(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "qos", 2,
                "descricao", "Exactly Once — PUBLISH→PUBREC→PUBREL→PUBCOMP, sem duplicatas",
                "uuid", pessoa.uuid().toString()));
    }

    /**
     * Retain=true — broker guarda esta mensagem.
     * Novos subscribers a recebem imediatamente ao assinar o tópico.
     *
     * POST /mensagens/retido
     */
    @PostMapping("/retido")
    public ResponseEntity<Map<String, Object>> retido(@Valid @RequestBody PessoaDTO dto) throws MqttException {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarRetido(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "retained", true,
                "descricao", "Broker guarda a última mensagem — novos subscribers a recebem imediatamente",
                "uuid", pessoa.uuid().toString()));
    }

    /**
     * Apaga a mensagem retida de um tópico (publica payload vazio com retain=true).
     *
     * DELETE /mensagens/retido?topico=pessoas/atualizacao
     */
    @DeleteMapping("/retido")
    public ResponseEntity<Map<String, Object>> limparRetido(
            @RequestParam(defaultValue = "pessoas/atualizacao") String topico) throws MqttException {
        publisher.limparRetido(topico);
        return ResponseEntity.ok(Map.of(
                "topico", topico,
                "descricao", "Mensagem retida removida — payload vazio com retain=true"));
    }

    /**
     * Exclusão via tópico dedicado.
     *
     * POST /mensagens/exclusao
     */
    @PostMapping("/exclusao")
    public ResponseEntity<Map<String, Object>> exclusao(@Valid @RequestBody PessoaDTO dto) throws MqttException {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.publicarExclusao(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "topico", "pessoas/exclusao",
                "uuid", pessoa.uuid().toString()));
    }

    /**
     * Publica um de cada cenário MQTT sequencialmente.
     *
     * POST /mensagens/todos-cenarios
     */
    @PostMapping("/todos-cenarios")
    public ResponseEntity<Map<String, Object>> todosCenarios() throws MqttException {
        publisher.publicarTodosCenarios(
                PessoaDTO.criar("Alice QoS0",   11911111111L, "Rua MQTT, 1"),
                PessoaDTO.criar("Bruno QoS1",   11922222222L, "Rua MQTT, 2"),
                PessoaDTO.criar("Carla QoS2",   11933333333L, "Rua MQTT, 3"),
                PessoaDTO.criar("Diego Retain", 11944444444L, "Rua MQTT, 4")
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "mensagem", "4 mensagens publicadas — QoS 0, QoS 1, QoS 2, Retain",
                "cenarios", new String[]{"QoS0", "QoS1", "QoS2", "Retain"}));
    }
}
