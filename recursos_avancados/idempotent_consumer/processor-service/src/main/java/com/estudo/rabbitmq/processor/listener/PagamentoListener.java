package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PagamentoDTO;
import com.estudo.rabbitmq.processor.entity.Pagamento;
import com.estudo.rabbitmq.processor.repository.PagamentoRepository;
import com.estudo.rabbitmq.processor.service.IdempotencyService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Consumer idempotente — processa cada mensagem EXATAMENTE UMA VEZ,
 * mesmo que o broker entregue a mesma mensagem múltiplas vezes.
 *
 * Fluxo:
 *
 *   1. Mensagem chega com messageId
 *   2. Verifica na tabela processed_messages se já foi processada
 *      → SIM: loga como duplicata, faz ACK (remove da fila), NÃO processa
 *      → NÃO: continua
 *   3. Processa a lógica de negócio (salva pagamento no banco)
 *   4. Registra o messageId na tabela de controle (UNIQUE constraint)
 *   5. ACK ao broker
 *
 * Por que ACK na duplicata?
 *   Se fizermos NACK, o broker reenvia — loop infinito de duplicatas.
 *   A mensagem já foi processada antes, então removê-la é o correto.
 *
 * Por que @Transactional?
 *   O INSERT do pagamento e o INSERT na tabela de controle devem ser
 *   atômicos. Se um falhar, o outro também é revertido.
 */
@Component
public class PagamentoListener {

    private static final Logger log = LoggerFactory.getLogger(PagamentoListener.class);

    private final IdempotencyService idempotencyService;
    private final PagamentoRepository pagamentoRepository;

    public PagamentoListener(IdempotencyService idempotencyService, PagamentoRepository pagamentoRepository) {
        this.idempotencyService = idempotencyService;
        this.pagamentoRepository = pagamentoRepository;
    }

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(
            @Payload PagamentoDTO dto,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CHANNEL) Channel channel) throws IOException {

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [CONSUMER] Mensagem recebida");
        log.info("║  messageId : {}", dto.messageId());
        log.info("║  pedidoId  : {}", dto.pedidoId());
        log.info("║  cliente   : {}", dto.cliente());
        log.info("║  valor     : R$ {}", String.format("%.2f", dto.valor()));
        log.info("╠══════════════════════════════════════════════════════════");

        // ── STEP 1: Check rápido — já foi processada? ─────────────────
        if (idempotencyService.isProcessed(dto.messageId())) {
            log.warn("║ [IDEMPOTENCY] ⚠ DUPLICATA DETECTADA — messageId={}", dto.messageId());
            log.warn("║               → Mensagem já processada anteriormente");
            log.warn("║               → ACK para remover da fila (sem reprocessar)");
            log.warn("╚══════════════════════════════════════════════════════════");

            channel.basicAck(deliveryTag, false);
            return;
        }

        // ── STEP 2: Processar + registrar (transacional) ──────────────
        try {
            processarPagamento(dto);

            // ── STEP 3: ACK ───────────────────────────────────────────
            channel.basicAck(deliveryTag, false);
            log.info("║ [CONSUMER] ✔ Pagamento processado e ACK enviado");
            log.info("╚══════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("║ [CONSUMER] ✘ Erro ao processar: {}", e.getMessage());
            log.error("║             → NACK para retentar");
            log.error("╚══════════════════════════════════════════════════════════");
            channel.basicNack(deliveryTag, false, true);
        }
    }

    @Transactional
    protected void processarPagamento(PagamentoDTO dto) {
        // Salva o pagamento (ação de negócio)
        Pagamento pagamento = new Pagamento(dto.pedidoId(), dto.cliente(), dto.valor());
        pagamentoRepository.save(pagamento);
        log.info("║  Pagamento salvo no banco | id={} pedido={}", pagamento.getId(), dto.pedidoId());

        // Registra como processado (controle de idempotência)
        boolean registrado = idempotencyService.markAsProcessed(dto.messageId());
        if (registrado) {
            log.info("║  messageId={} registrado na tabela de controle", dto.messageId());
        } else {
            log.warn("║  messageId={} já existia (race condition tratada)", dto.messageId());
        }
    }
}
