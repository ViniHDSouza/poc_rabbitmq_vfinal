package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PagamentoDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pagamentos")
public class PagamentoController {

    private static final Logger log = LoggerFactory.getLogger(PagamentoController.class);
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")    private String exchange;
    @Value("${rabbitmq.routing-key}") private String routingKey;

    public PagamentoController(RabbitTemplate rabbitTemplate) { this.rabbitTemplate = rabbitTemplate; }

    /**
     * Publica um pagamento com messageId gerado automaticamente.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PagamentoDTO publicar(@Valid @RequestBody PagamentoDTO dto) {
        var pagamento = PagamentoDTO.criar(dto.pedidoId(), dto.cliente(), dto.valor());
        rabbitTemplate.convertAndSend(exchange, routingKey, pagamento);

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [SENDER] Pagamento publicado");
        log.info("║  messageId : {}", pagamento.messageId());
        log.info("║  pedidoId  : {}", pagamento.pedidoId());
        log.info("║  cliente   : {}", pagamento.cliente());
        log.info("║  valor     : R$ {}", String.format("%.2f", pagamento.valor()));
        log.info("╚══════════════════════════════════════════════════════════");

        return pagamento;
    }

    /**
     * EXPERIMENTO — publica a MESMA mensagem N vezes (simula duplicidade do broker).
     *
     * Em produção, duplicidades ocorrem por:
     *   - Redelivery após timeout de ACK
     *   - Publisher retry sem publisher confirms
     *   - Network partition + reconexão
     *
     * O consumer idempotente deve processar apenas a primeira e ignorar as demais.
     */
    @PostMapping("/duplicar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PagamentoDTO publicarDuplicada(
            @Valid @RequestBody PagamentoDTO dto,
            @RequestParam(defaultValue = "3") int vezes) {

        var pagamento = PagamentoDTO.criar(dto.pedidoId(), dto.cliente(), dto.valor());

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [SENDER] Simulando DUPLICIDADE — {} envios do mesmo messageId", vezes);
        log.info("║  messageId : {}", pagamento.messageId());
        log.info("║  pedidoId  : {}", pagamento.pedidoId());
        log.info("║  cliente   : {}", pagamento.cliente());
        log.info("║  valor     : R$ {}", String.format("%.2f", pagamento.valor()));
        log.info("╠══════════════════════════════════════════════════════════");

        for (int i = 1; i <= vezes; i++) {
            rabbitTemplate.convertAndSend(exchange, routingKey, pagamento);
            log.info("║  [{}/{}] Enviada (mesmo messageId={})", i, vezes, pagamento.messageId());
        }

        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  O consumer deve processar APENAS a 1ª e ignorar as demais");
        log.info("╚══════════════════════════════════════════════════════════");

        return pagamento;
    }

    /**
     * Publica um lote de pagamentos únicos.
     */
    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "5") int quantidade) {
        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [SENDER] Publicando lote de {} pagamentos únicos", quantidade);
        log.info("╠══════════════════════════════════════════════════════════");

        for (int i = 1; i <= quantidade; i++) {
            var pagamento = PagamentoDTO.criar("PED-" + i, "Cliente-" + i, 100.0 * i);
            rabbitTemplate.convertAndSend(exchange, routingKey, pagamento);
            log.info("║  [{}/{}] messageId={} pedido={} valor=R${}",
                    i, quantidade, pagamento.messageId(), pagamento.pedidoId(), String.format("%.2f", pagamento.valor()));
        }

        log.info("╚══════════════════════════════════════════════════════════");
        return "%d pagamentos únicos publicados.".formatted(quantidade);
    }
}