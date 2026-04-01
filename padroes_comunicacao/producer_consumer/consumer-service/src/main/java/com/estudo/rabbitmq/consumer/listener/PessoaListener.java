package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.estudo.rabbitmq.consumer.service.PessoaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Listener que consome mensagens da fila pessoa.queue.
 *
 * O @RabbitListener faz o Spring AMQP:
 *   1. Conectar-se à fila pessoa.queue ao subir o contexto
 *   2. Aguardar mensagens em modo push (o broker empurra para o consumer)
 *   3. Desserializar o JSON → PessoaDTO usando o Jackson2JsonMessageConverter
 *   4. Chamar o método processar() a cada mensagem recebida
 *   5. Enviar ACK automático ao broker ao finalizar o método sem exceção
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    private final PessoaService pessoaService;

    public PessoaListener(PessoaService pessoaService) {
        this.pessoaService = pessoaService;
    }

    @RabbitListener(queues = "pessoa.queue")
    public void processar(PessoaDTO pessoa) {
        log.info("[CONSUMER] Mensagem recebida → id={} nome={} telefone={} endereco={}",
                pessoa.id(), pessoa.nome(), pessoa.telefone(), pessoa.endereco());

        pessoaService.processar(pessoa);
    }
}
