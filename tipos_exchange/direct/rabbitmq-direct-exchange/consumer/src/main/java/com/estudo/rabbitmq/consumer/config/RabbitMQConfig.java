package com.estudo.rabbitmq.consumer.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}") private String exchange;
    @Value("${rabbitmq.queue.cadastro}") private String cadastroQueue;
    @Value("${rabbitmq.queue.remocao}") private String remocaoQueue;
    @Value("${rabbitmq.routing-key.cadastro}") private String cadastroKey;
    @Value("${rabbitmq.routing-key.remocao}") private String remocaoKey;

    @Bean public DirectExchange pessoaDirectExchange() { return ExchangeBuilder.directExchange(exchange).durable(true).build(); }
    @Bean public Queue cadastroQueue() { return QueueBuilder.durable(cadastroQueue).build(); }
    @Bean public Queue remocaoQueue() { return QueueBuilder.durable(remocaoQueue).build(); }
    @Bean public Binding bindingCadastro(Queue cadastroQueue, DirectExchange pessoaDirectExchange) { return BindingBuilder.bind(cadastroQueue).to(pessoaDirectExchange).with(cadastroKey); }
    @Bean public Binding bindingRemocao(Queue remocaoQueue, DirectExchange pessoaDirectExchange) { return BindingBuilder.bind(remocaoQueue).to(pessoaDirectExchange).with(remocaoKey); }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
