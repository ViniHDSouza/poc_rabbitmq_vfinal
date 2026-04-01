package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class TodosEventosListener {
    private static final Logger log = LoggerFactory.getLogger(TodosEventosListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.todos-eventos}")
    public void receber(PessoaDTO pessoa, @Header("amqp_receivedRoutingKey") String routingKey) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [TODOS EVENTOS] pessoa.# — captura tudo");
        log.info("║  RoutingKey : {}", routingKey);
        log.info("║  UUID       : {}", pessoa.uuid());
        log.info("║  Nome       : {}", pessoa.nome());
        log.info("╚══════════════════════════════════════════════════");
    }
}
