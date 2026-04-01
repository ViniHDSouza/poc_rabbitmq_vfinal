package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class RemocaoListener {
    private static final Logger log = LoggerFactory.getLogger(RemocaoListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.remocao}")
    public void receber(PessoaDTO pessoa, @Header("amqp_receivedRoutingKey") String routingKey) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [REMOÇÃO] pessoa.remocao.* — somente remoções");
        log.info("║  RoutingKey : {}", routingKey);
        log.info("║  UUID       : {}", pessoa.uuid());
        log.info("║  Nome       : {}", pessoa.nome());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  ✔ Remoção processada com sucesso");
        log.info("╚══════════════════════════════════════════════════");
    }
}
