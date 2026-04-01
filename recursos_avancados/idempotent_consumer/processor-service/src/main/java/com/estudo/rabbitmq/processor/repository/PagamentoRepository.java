package com.estudo.rabbitmq.processor.repository;

import com.estudo.rabbitmq.processor.entity.Pagamento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {}
