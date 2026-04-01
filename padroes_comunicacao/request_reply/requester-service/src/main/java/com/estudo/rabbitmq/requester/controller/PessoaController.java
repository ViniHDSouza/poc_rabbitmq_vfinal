package com.estudo.rabbitmq.requester.controller;

import com.estudo.rabbitmq.requester.dto.PessoaRequestDTO;
import com.estudo.rabbitmq.requester.dto.PessoaResponseDTO;
import com.estudo.rabbitmq.requester.service.PessoaRequesterService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/consultar")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);

    private final PessoaRequesterService requesterService;

    public PessoaController(PessoaRequesterService requesterService) {
        this.requesterService = requesterService;
    }

    /**
     * Recebe uma Pessoa, envia para o Replier via RabbitMQ e retorna a resposta.
     *
     * POST /consultar
     * Body: { "nome": "Ana", "telefone": 11911112222, "endereco": "Rua Nova, 5" }
     *
     * Diferença chave vs Producer/Consumer e Pub/Sub:
     * - Esta chamada é SÍNCRONA do ponto de vista do cliente HTTP.
     *   O HTTP espera até o Replier processar e responder via RabbitMQ.
     * - Internamente é assíncrona via mensageria, mas o resultado
     *   é entregue de volta ao chamador na mesma requisição.
     */
    @PostMapping
    public ResponseEntity<PessoaResponseDTO> consultar(@Valid @RequestBody PessoaRequestDTO requestDTO) {

        PessoaRequestDTO request = requestDTO.uuid() == null
                ? PessoaRequestDTO.criar(requestDTO.nome(), requestDTO.telefone(), requestDTO.endereco())
                : requestDTO;

        log.info("[REQUESTER] Iniciando Request/Reply | uuid={}", request.uuid());

        PessoaResponseDTO response = requesterService.enviarRequisicao(request);

        return ResponseEntity.ok(response);
    }

    /**
     * Envia múltiplas requisições sequenciais para demonstrar o correlationId em ação.
     * Cada requisição tem seu próprio correlationId e aguarda sua própria resposta.
     *
     * POST /consultar/lote?quantidade=3
     */
    @PostMapping("/lote")
    public ResponseEntity<String> consultarLote(@RequestParam(defaultValue = "3") int quantidade) {

        StringBuilder resultado = new StringBuilder();

        for (int i = 1; i <= quantidade; i++) {
            PessoaRequestDTO request = PessoaRequestDTO.criar(
                    "Pessoa " + i,
                    11900000000L + i,
                    "Rua Request/Reply, " + i
            );

            log.info("[REQUESTER] Lote [{}/{}] | uuid={}", i, quantidade, request.uuid());

            PessoaResponseDTO response = requesterService.enviarRequisicao(request);

            resultado.append("[%d/%d] uuid=%s | status=%s | mensagem=%s%n"
                    .formatted(i, quantidade, response.uuid(), response.status(), response.mensagem()));
        }

        return ResponseEntity.ok(resultado.toString());
    }
}
