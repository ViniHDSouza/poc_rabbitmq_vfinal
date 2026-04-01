package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class CadastroListener {
    private static final Logger log = LoggerFactory.getLogger(CadastroListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.cadastro}")
    public void receber(PessoaDTO pessoa, @Header("amqp_receivedRoutingKey") String routingKey) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [CADASTRO] pessoa.cadastro.* — somente cadastros");
        log.info("║  RoutingKey : {}", routingKey);
        log.info("║  UUID       : {}", pessoa.uuid());
        log.info("║  Nome       : {}", pessoa.nome());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  ✔ Cadastro processado com sucesso");
        log.info("╚══════════════════════════════════════════════════");
    }
}
