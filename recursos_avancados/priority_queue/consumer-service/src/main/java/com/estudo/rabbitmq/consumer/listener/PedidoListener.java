package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PedidoDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer que processa pedidos respeitando a prioridade definida na fila.
 *
 * O processamento é propositalmente LENTO (1s por pedido) para que as
 * mensagens acumulem na fila e a prioridade tenha efeito visível.
 *
 * Sem o delay:
 *   - Consumer processa mais rápido do que o sender publica
 *   - Fila nunca tem mais de 1 mensagem
 *   - Prioridade não tem efeito (sem mensagens para comparar)
 *
 * Com o delay de 1s:
 *   - 10 mensagens chegam de uma vez (endpoint /demonstracao)
 *   - Consumer pega a de maior prioridade primeiro
 *   - Nos logs: P9, P8, P7... em vez de P0, P1, P2...
 */
@Component
public class PedidoListener {

    private static final Logger log = LoggerFactory.getLogger(PedidoListener.class);
    private final AtomicInteger ordem = new AtomicInteger(0);

    @Value("${consumer.processing-time-ms}")
    private long processingTimeMs;

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(PedidoDTO pedido) {
        int posicao = ordem.incrementAndGet();

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [CONSUMER] Pedido #{} recebido", posicao);
        log.info("║  Prioridade : {} {}", pedido.prioridade(), descricaoPrioridade(pedido.prioridade()));
        log.info("║  UUID       : {}", pedido.uuid());
        log.info("║  Cliente    : {}", pedido.cliente());
        log.info("║  Descrição  : {}", pedido.descricao());
        log.info("║  Valor      : R$ {}", String.format("%.2f", pedido.valor()));
        log.info("╠══════════════════════════════════════════════════════════");

        // Simula processamento lento
        if (processingTimeMs > 0) {
            try { Thread.sleep(processingTimeMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        log.info("║  ✔ Processado com sucesso (posição {} na ordem de consumo)", posicao);
        log.info("╚══════════════════════════════════════════════════════════");
    }

    private String descricaoPrioridade(int prioridade) {
        return switch (prioridade) {
            case 0 -> "(MÍNIMA — Normal)";
            case 1, 2 -> "(BAIXA)";
            case 3, 4 -> "(MÉDIA)";
            case 5, 6 -> "(ALTA)";
            case 7, 8 -> "(MUITO ALTA)";
            case 9 -> "(MÁXIMA — Emergência)";
            default -> "";
        };
    }
}
