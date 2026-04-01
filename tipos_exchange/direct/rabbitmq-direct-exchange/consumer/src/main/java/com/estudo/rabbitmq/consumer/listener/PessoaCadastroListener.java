package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PessoaCadastroListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaCadastroListener.class);

    @RabbitListener(queues = "${rabbitmq.queue.cadastro}")
    public void receber(PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [CONSUMER] Mensagem de CADASTRO recebida");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("║  Endereço : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════");
        log.info("║  Processando cadastro...");
        log.info("║  ✔ Cadastro processado com sucesso");
        log.info("╚══════════════════════════════════════════════════");
    }
}
