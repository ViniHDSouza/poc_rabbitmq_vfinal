package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SmsListener {
    private static final Logger log = LoggerFactory.getLogger(SmsListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.sms}")
    public void receber(PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [SMS] Pessoa recebida para envio de SMS");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  Simulando envio de SMS de confirmação para {}...", pessoa.nome());
        log.info("║  ✔ SMS enviado com sucesso | uuid={}", pessoa.uuid());
        log.info("╚══════════════════════════════════════════════════");
    }
}
