package com.estudo.rabbitmq.sender.config;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    @Value("${rabbitmq.queue}")
    private String queue;

    @Bean
    public DirectExchange pessoaExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(routingKey);
    }

    // ----------------------------------------------------------------
    // Converter com mapeamento de tipo por nome lógico.
    //
    // Problema sem o mapeamento:
    //   O Sender grava no header __TypeId__ o nome completo da classe:
    //   "com.estudo.rabbitmq.sender.dto.PessoaDTO"
    //   O Consumer tenta carregar essa classe — que não existe no seu
    //   classpath — e lança ClassNotFoundException.
    //
    // Solução — nome lógico compartilhado:
    //   Sender  escreve  __TypeId__ = "pessoa"
    //   Consumer lê      "pessoa"  → PessoaDTO (classe local)
    // ----------------------------------------------------------------
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of("pessoa", PessoaDTO.class));
        typeMapper.setTrustedPackages("*");

        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
