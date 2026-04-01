package com.estudo.rabbitmq.consumer.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do RabbitMQ no Consumer.
 *
 * O Consumer NÃO redeclara a queue, exchange nem o binding — essa
 * responsabilidade é do Producer. Aqui registramos apenas o conversor
 * JSON para que o @RabbitListener consiga desserializar o payload
 * recebido como PessoaDTO automaticamente.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "pessoa.exchange";
    public static final String QUEUE_NAME    = "pessoa.queue";
    public static final String ROUTING_KEY   = "pessoa";

    /**
     * Mesmo conversor usado no Producer.
     * O Spring AMQP usa o header "__TypeId__" gravado na mensagem
     * para saber qual classe instanciar na desserialização.
     */
    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange pessoaExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
