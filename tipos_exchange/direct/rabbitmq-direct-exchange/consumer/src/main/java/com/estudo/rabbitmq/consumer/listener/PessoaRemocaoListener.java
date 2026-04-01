package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PessoaRemocaoListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaRemocaoListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.remocao}")
    public void receber(PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [CONSUMER] Mensagem de REMOÇÃO recebida");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  Processando remoção...");
        log.info("║  ✔ Remoção processada com sucesso");
        log.info("╚══════════════════════════════════════════════════");
    }
}
