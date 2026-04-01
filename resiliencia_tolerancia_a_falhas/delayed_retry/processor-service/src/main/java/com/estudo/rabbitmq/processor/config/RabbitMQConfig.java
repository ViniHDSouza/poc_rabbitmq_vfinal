package com.estudo.rabbitmq.processor.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia do Delayed Retry com TTL + DLQ (trampolim via broker).
 *
 * Diferença fundamental em relação ao Retry Pattern (POC anterior):
 *
 *   RETRY PATTERN (em memória):
 *     Mensagem falha → Spring Retry aguarda em Thread.sleep() → tenta de novo
 *     A mensagem NUNCA sai da memória do consumer durante os retries.
 *     Se o consumer cair, a mensagem é perdida.
 *
 *   DELAYED RETRY (via broker — este projeto):
 *     Mensagem falha → NACK(requeue=false) → vai para WAIT QUEUE (com TTL)
 *     → TTL expira → DLX roteia de volta para a FILA PRINCIPAL → tenta de novo
 *     A mensagem está SEMPRE no broker, nunca em memória. Se o consumer cair,
 *     nada é perdido — a mensagem continua na fila esperando.
 *
 * Topologia:
 *
 *   pessoa.exchange ──► pessoa.queue (fila principal)
 *        ▲                    │
 *        │                    │ falha → NACK(requeue=false)
 *        │                    ▼
 *        │              pessoa.queue.wait (wait queue com TTL=5s)
 *        │                    │
 *        │                    │ TTL expira → DLX
 *        │                    ▼
 *        └──────────── pessoa.exchange (DLX da wait queue = exchange principal)
 *
 *   Após max-retries esgotados:
 *     pessoa.queue → NACK → pessoa.queue.parking-lot (fila morta definitiva)
 */
@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")      private String exchange;
    @Value("${rabbitmq.routing-key}")   private String routingKey;
    @Value("${rabbitmq.queue}")         private String queue;
    @Value("${rabbitmq.wait-queue}")    private String waitQueue;
    @Value("${rabbitmq.wait-ttl}")      private int waitTtl;
    @Value("${rabbitmq.parking-lot}")   private String parkingLot;

    // ----------------------------------------------------------------
    // Exchange principal — recebe mensagens do sender E mensagens
    // que voltam da wait queue após o TTL expirar (DLX da wait queue)
    // ----------------------------------------------------------------
    @Bean
    public DirectExchange pessoaExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    // ----------------------------------------------------------------
    // FILA PRINCIPAL — quando uma mensagem falha e ainda tem retries
    // sobrando, o listener faz NACK(requeue=false). O DLX desta fila
    // aponta para um exchange intermediário que roteia para a wait queue.
    //
    // Quando os retries se esgotam, o listener publica diretamente
    // na parking-lot (fila morta definitiva).
    // ----------------------------------------------------------------
    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "")          // default exchange
                .withArgument("x-dead-letter-routing-key", waitQueue) // vai para wait queue
                .build();
    }

    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(routingKey);
    }

    // ----------------------------------------------------------------
    // WAIT QUEUE (trampolim) — fila de espera com TTL fixo.
    //
    // Mensagens rejeitadas da fila principal caem aqui e ficam "dormindo"
    // por wait-ttl milissegundos. Quando o TTL expira, o broker as
    // encaminha via DLX de volta para o exchange principal → fila principal.
    //
    // Nenhum consumer escuta esta fila — ela existe apenas como "sala de espera".
    //
    // É o coração do padrão: o delay acontece no broker, não em memória.
    // ----------------------------------------------------------------
    @Bean
    public Queue waitQueue() {
        return QueueBuilder.durable(waitQueue)
                .withArgument("x-message-ttl",          waitTtl)     // delay de 5s
                .withArgument("x-dead-letter-exchange",  exchange)    // volta para exchange principal
                .withArgument("x-dead-letter-routing-key", routingKey) // com a routing key original
                .build();
    }

    // ----------------------------------------------------------------
    // PARKING LOT — fila morta definitiva para mensagens que esgotaram
    // todas as tentativas. Equivalente à DLQ dos projetos anteriores,
    // mas com nome diferente para distinguir da wait queue (que também
    // é tecnicamente uma DLQ, mas temporária).
    // ----------------------------------------------------------------
    @Bean
    public Queue parkingLotQueue() {
        return QueueBuilder.durable(parkingLot).build();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
