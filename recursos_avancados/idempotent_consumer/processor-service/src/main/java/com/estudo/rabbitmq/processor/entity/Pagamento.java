package com.estudo.rabbitmq.processor.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Representa um pagamento processado pelo consumer.
 * Simula a "ação de negócio" que não deve acontecer duas vezes.
 */
@Entity
@Table(name = "pagamentos")
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String pedidoId;

    @Column(nullable = false)
    private String cliente;

    @Column(nullable = false)
    private Double valor;

    @Column(nullable = false)
    private Instant processedAt;

    protected Pagamento() {}

    public Pagamento(String pedidoId, String cliente, Double valor) {
        this.pedidoId = pedidoId;
        this.cliente = cliente;
        this.valor = valor;
        this.processedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPedidoId() { return pedidoId; }
    public String getCliente() { return cliente; }
    public Double getValor() { return valor; }
    public Instant getProcessedAt() { return processedAt; }
}
