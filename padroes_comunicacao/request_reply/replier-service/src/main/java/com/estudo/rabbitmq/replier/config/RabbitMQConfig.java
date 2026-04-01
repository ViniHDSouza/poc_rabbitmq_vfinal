package com.estudo.rabbitmq.replier.config;

import com.estudo.rabbitmq.replier.dto.PessoaRequestDTO;
import com.estudo.rabbitmq.replier.dto.PessoaResponseDTO;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.request-queue}")
    private String requestQueue;
    // ----------------------------------------------------------------
    // Converter com mapeamento de tipo explícito — mesmos nomes lógicos
    // do Requester para garantir que o __TypeId__ header seja legível
    // entre serviços com pacotes Java diferentes.
    //
    // Sem isso: Replier grava "com.estudo.rabbitmq.replier.dto.PessoaResponseDTO"
    //           Requester não encontra essa classe → MessageConversionException
    //
    // Com isso:  Replier grava "pessoaResponse"
    //            Requester lê  "pessoaResponse" → sua classe local ✔
    // ----------------------------------------------------------------
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of(
                "pessoaRequest",  PessoaRequestDTO.class,
                "pessoaResponse", PessoaResponseDTO.class
        ));
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

    @Bean
    public Queue pessoaRequestQueue() {
        return QueueBuilder.durable(requestQueue).build();
    }
}
