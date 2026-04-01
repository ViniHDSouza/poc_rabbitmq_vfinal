package com.estudo.rabbitmq.sender.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    // ----------------------------------------------------------------
    // O Sender declara APENAS o exchange.
    //
    // Por que não declara a fila?
    //   A fila pessoa.queue é declarada pelo Processor COM os argumentos
    //   x-dead-letter-exchange e x-dead-letter-routing-key.
    //   O RabbitMQ não permite redeclarar uma fila existente com atributos
    //   diferentes — lança erro 406 PRECONDITION_FAILED.
    //   Se o Sender declarasse a fila SEM esses argumentos e o Processor
    //   já a tivesse criado COM eles (ou vice-versa), a conexão seria
    //   derrubada com channel error.
    //
    // O Sender publica mensagens no exchange com a routing key.
    // O broker já sabe, pelo binding criado pelo Processor, que deve
    // rotear para pessoa.queue. O Sender não precisa conhecer a fila.
    // ----------------------------------------------------------------
    @Bean
    public DirectExchange pessoaExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
