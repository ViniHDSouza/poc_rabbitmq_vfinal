package com.estudo.rabbitmq.processor.service;

import com.estudo.rabbitmq.processor.entity.ProcessedMessage;
import com.estudo.rabbitmq.processor.repository.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Serviço de controle de idempotência.
 *
 * Dois níveis de proteção contra duplicatas:
 *
 *   1. CHECK RÁPIDO (SELECT):
 *      existsByMessageId() → se já existe, retorna true sem INSERT
 *
 *   2. UNIQUE CONSTRAINT (INSERT):
 *      Se duas threads passarem pelo check simultaneamente,
 *      apenas uma consegue inserir. A outra recebe
 *      DataIntegrityViolationException e é tratada como duplicata.
 *
 * Essa abordagem em dois níveis é o padrão de produção porque:
 *   - O SELECT evita INSERTs desnecessários (performance)
 *   - O UNIQUE garante atomicidade mesmo com concorrência (corretude)
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ProcessedMessageRepository repository;

    public IdempotencyService(ProcessedMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * Verifica se a mensagem já foi processada.
     */
    public boolean isProcessed(UUID messageId) {
        return repository.existsByMessageId(messageId);
    }

    /**
     * Registra que a mensagem foi processada com sucesso.
     *
     * @return true se registrou (primeira vez), false se duplicata (UNIQUE violation)
     */
    @Transactional
    public boolean markAsProcessed(UUID messageId) {
        try {
            repository.save(new ProcessedMessage(messageId, "PROCESSED"));
            return true;
        } catch (DataIntegrityViolationException e) {
            // UNIQUE constraint violation — outra thread/instância já processou
            log.warn("[IDEMPOTENCY] Race condition detectada — messageId={} já inserido por outra thread", messageId);
            return false;
        }
    }
}
