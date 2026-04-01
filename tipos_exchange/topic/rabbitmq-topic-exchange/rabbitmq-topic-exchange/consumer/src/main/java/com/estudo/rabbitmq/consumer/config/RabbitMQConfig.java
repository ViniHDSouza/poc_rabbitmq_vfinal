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
    @Value("${rabbitmq.queue.todos-eventos}") private String todosQueue;
    @Value("${rabbitmq.queue.cadastro}") private String cadastroQueue;
    @Value("${rabbitmq.queue.remocao}") private String remocaoQueue;

    @Bean public TopicExchange topicExchange() { return new TopicExchange(exchange, true, false); }
    @Bean public Queue todosEventosQueue() { return QueueBuilder.durable(todosQueue).build(); }
    @Bean public Queue cadastroQueue() { return QueueBuilder.durable(cadastroQueue).build(); }
    @Bean public Queue remocaoQueue() { return QueueBuilder.durable(remocaoQueue).build(); }
    @Bean public Binding bindingTodos(Queue todosEventosQueue, TopicExchange topicExchange) { return BindingBuilder.bind(todosEventosQueue).to(topicExchange).with("pessoa.#"); }
    @Bean public Binding bindingCadastro(Queue cadastroQueue, TopicExchange topicExchange) { return BindingBuilder.bind(cadastroQueue).to(topicExchange).with("pessoa.cadastro.*"); }
    @Bean public Binding bindingRemocao(Queue remocaoQueue, TopicExchange topicExchange) { return BindingBuilder.bind(remocaoQueue).to(topicExchange).with("pessoa.remocao.*"); }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
