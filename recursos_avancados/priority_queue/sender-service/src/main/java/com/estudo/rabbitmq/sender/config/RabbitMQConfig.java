package com.estudo.rabbitmq.sender.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")    private String exchange;
    @Value("${rabbitmq.routing-key}") private String routingKey;
    @Value("${rabbitmq.queue}")       private String queue;

    // ----------------------------------------------------------------
    // Exchange principal
    // ----------------------------------------------------------------
    @Bean
    public DirectExchange pedidoExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    // ----------------------------------------------------------------
    // FILA COM PRIORIDADE — x-max-priority=10
    //
    // ESSENCIAL: sem este argumento, o broker ignora completamente
    // o campo priority da mensagem e entrega em FIFO puro.
    //
    // x-max-priority define o número máximo de níveis de prioridade
    // que a fila suporta. O RabbitMQ cria internamente uma sub-fila
    // para cada nível (0 até max-priority), e sempre entrega primeiro
    // as mensagens do nível mais alto com mensagens disponíveis.
    //
    // Recomendação do RabbitMQ: use no máximo 10 níveis (0-9).
    // Valores maiores funcionam mas consomem mais memória e CPU.
    // ----------------------------------------------------------------
    @Bean
    public Queue pedidoQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-max-priority", 10)
                .build();
    }

    @Bean
    public Binding pedidoBinding(Queue pedidoQueue, DirectExchange pedidoExchange) {
        return BindingBuilder.bind(pedidoQueue).to(pedidoExchange).with(routingKey);
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
