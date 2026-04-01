package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EmailListener {
    private static final Logger log = LoggerFactory.getLogger(EmailListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.email}")
    public void receber(PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [EMAIL] Pessoa recebida para envio de e-mail");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("║  Endereço : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  Simulando envio de e-mail de boas-vindas para {}...", pessoa.nome());
        log.info("║  ✔ E-mail enviado com sucesso | uuid={}", pessoa.uuid());
        log.info("╚══════════════════════════════════════════════════");
    }
}
