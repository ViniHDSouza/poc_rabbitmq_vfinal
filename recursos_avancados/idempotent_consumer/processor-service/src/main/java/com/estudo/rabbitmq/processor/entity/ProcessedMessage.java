package com.estudo.rabbitmq.processor.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tabela de controle de idempotência.
 *
 * Cada messageId processado com sucesso é registrado aqui.
 * Antes de processar uma mensagem, o consumer verifica se o messageId
 * já existe nesta tabela. Se existir → duplicata → ignora.
 *
 * A UNIQUE constraint no messageId garante que, mesmo com race condition
 * entre threads concorrentes, apenas uma consegue inserir — a outra
 * recebe DataIntegrityViolationException e trata como duplicata.
 */
@Entity
@Table(name = "processed_messages", indexes = {
        @Index(name = "idx_message_id", columnList = "messageId", unique = true)
})
public class ProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID messageId;

    @Column(nullable = false)
    private Instant processedAt;

    @Column(nullable = false)
    private String status;

    protected ProcessedMessage() {}

    public ProcessedMessage(UUID messageId, String status) {
        this.messageId = messageId;
        this.processedAt = Instant.now();
        this.status = status;
    }

    public Long getId() { return id; }
    public UUID getMessageId() { return messageId; }
    public Instant getProcessedAt() { return processedAt; }
    public String getStatus() { return status; }
}
