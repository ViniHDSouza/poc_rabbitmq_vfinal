package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PedidoDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private static final Logger log = LoggerFactory.getLogger(PedidoController.class);
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")    private String exchange;
    @Value("${rabbitmq.routing-key}") private String routingKey;

    public PedidoController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica um pedido com prioridade definida no body.
     *
     * O MessagePostProcessor injeta a prioridade no header AMQP da mensagem.
     * O broker ordena as mensagens na fila por prioridade (maior primeiro).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PedidoDTO publicar(@Valid @RequestBody PedidoDTO dto) {
        var pedido = PedidoDTO.criar(dto.descricao(), dto.valor(), dto.cliente(), dto.prioridade());

        MessagePostProcessor prioritySetter = message -> {
            message.getMessageProperties().setPriority(pedido.prioridade());
            return message;
        };

        rabbitTemplate.convertAndSend(exchange, routingKey, pedido, prioritySetter);
        log.info("[SENDER] Pedido publicado | uuid={} prioridade={} cliente={} descricao={}",
                pedido.uuid(), pedido.prioridade(), pedido.cliente(), pedido.descricao());
        return pedido;
    }

    /**
     * EXPERIMENTO PRINCIPAL — publica 10 pedidos com prioridades diferentes
     * em uma rajada, TODOS de uma vez. O consumer está parado (ou lento),
     * então as mensagens se acumulam na fila.
     *
     * Quando o consumer começar a processar, as mensagens de prioridade
     * mais alta serão entregues PRIMEIRO, independente da ordem de publicação.
     *
     * Ordem de publicação:  P0, P1, P2, P3, P4, P5, P6, P7, P8, P9
     * Ordem de consumo:     P9, P8, P7, P6, P5, P4, P3, P2, P1, P0
     */
    @PostMapping("/demonstracao")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<PedidoDTO> demonstracao() {
        List<PedidoDTO> pedidos = new ArrayList<>();

        String[] clientes   = {"Normal", "Bronze", "Prata", "Prata+", "Ouro", "Ouro+", "Platina", "Diamante", "VIP", "Emergência"};
        double[] valores    = {50, 100, 200, 350, 500, 750, 1000, 2500, 5000, 10000};

        // Publica na ordem 0→9 (menor→maior prioridade)
        for (int prioridade = 0; prioridade <= 9; prioridade++) {
            var pedido = PedidoDTO.criar(
                    "Pedido " + clientes[prioridade],
                    valores[prioridade],
                    clientes[prioridade],
                    prioridade
            );

            int p = prioridade;
            MessagePostProcessor prioritySetter = message -> {
                message.getMessageProperties().setPriority(p);
                return message;
            };

            rabbitTemplate.convertAndSend(exchange, routingKey, pedido, prioritySetter);
            pedidos.add(pedido);
            log.info("[SENDER] [{}/10] Publicado | prioridade={} cliente={} valor=R${}",
                    prioridade + 1, prioridade, clientes[prioridade], valores[prioridade]);
        }

        log.info("[SENDER] 10 pedidos publicados na ordem P0→P9");
        log.info("[SENDER] O consumer vai recebê-los na ordem P9→P0 (prioridade decrescente)");

        return pedidos;
    }
}
