package com.estudo.rabbitmq.producer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do RabbitMQ no Producer.
 *
 * Responsabilidades:
 *  - Declarar a Queue durável (pessoa.queue)
 *  - Declarar a DirectExchange (pessoa.exchange)
 *  - Criar o Binding entre exchange e queue usando a routing key "pessoa"
 *  - Registrar o conversor JSON para serializar PessoaDTO como JSON nas mensagens
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME  = "pessoa.exchange";
    public static final String QUEUE_NAME     = "pessoa.queue";
    public static final String ROUTING_KEY    = "pessoa";

    /**
     * Fila durável: sobrevive a reinicializações do broker.
     */
    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    /**
     * Direct Exchange: entrega a mensagem na fila cuja binding key
     * corresponde exatamente à routing key enviada pelo producer.
     */
    @Bean
    public DirectExchange pessoaExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    /**
     * Binding: liga a exchange à fila usando a routing key "pessoa".
     *
     * Fluxo: producer → pessoa.exchange --[pessoa]--> pessoa.queue
     */
    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder
                .bind(pessoaQueue)
                .to(pessoaExchange)
                .with(ROUTING_KEY);
    }

    /**
     * Conversor que serializa/desserializa objetos Java como JSON.
     * O RabbitTemplate vai usar este bean automaticamente.
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
