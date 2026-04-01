package com.estudo.rabbitmq.processor.repository;

import com.estudo.rabbitmq.processor.entity.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    boolean existsByMessageId(UUID messageId);
}
