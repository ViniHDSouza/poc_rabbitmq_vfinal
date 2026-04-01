package com.estudo.rabbitmq.processor.controller;

import com.estudo.rabbitmq.processor.entity.Pagamento;
import com.estudo.rabbitmq.processor.entity.ProcessedMessage;
import com.estudo.rabbitmq.processor.repository.PagamentoRepository;
import com.estudo.rabbitmq.processor.repository.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoint para verificar o estado do banco após os testes.
 * Permite confirmar que mesmo com mensagens duplicadas,
 * o pagamento foi processado apenas uma vez.
 */
@RestController
@RequestMapping("/status")
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final PagamentoRepository pagamentoRepository;
    private final ProcessedMessageRepository processedMessageRepository;

    public StatusController(PagamentoRepository pagamentoRepository,
                            ProcessedMessageRepository processedMessageRepository) {
        this.pagamentoRepository = pagamentoRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    @GetMapping
    public Map<String, Object> status() {
        long totalPagamentos = pagamentoRepository.count();
        long totalMensagens = processedMessageRepository.count();
        List<Pagamento> pagamentos = pagamentoRepository.findAll();
        List<ProcessedMessage> mensagens = processedMessageRepository.findAll();

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [STATUS] Consulta ao banco de dados");
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  Pagamentos processados : {}", totalPagamentos);
        log.info("║  Mensagens registradas  : {}", totalMensagens);
        log.info("╠══════════════════════════════════════════════════════════");

        for (Pagamento p : pagamentos) {
            log.info("║  [PAGAMENTO] id={} pedido={} cliente={} valor=R${}",
                    p.getId(), p.getPedidoId(), p.getCliente(), String.format("%.2f", p.getValor()));
        }

        for (ProcessedMessage m : mensagens) {
            log.info("║  [MENSAGEM]  messageId={} status={} processedAt={}",
                    m.getMessageId(), m.getStatus(), m.getProcessedAt());
        }

        log.info("╚══════════════════════════════════════════════════════════");

        return Map.of(
                "pagamentos_processados", totalPagamentos,
                "mensagens_registradas", totalMensagens,
                "pagamentos", pagamentos,
                "mensagens", mensagens
        );
    }
}