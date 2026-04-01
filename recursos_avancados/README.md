# Recursos Avançados do RabbitMQ

Módulo que explora funcionalidades avançadas do RabbitMQ que vão além dos padrões básicos de comunicação.

## Projetos

### 1. [Priority Queue](./priority_queue)

Filas com prioridade — mensagens mais urgentes são entregues primeiro, independente da ordem de publicação. Demonstra `x-max-priority`, `MessagePostProcessor`, e a importância do `prefetch=1`.

**Portas:** Sender `8112` | Consumer `8113`

### 2. [Idempotent Consumer](./idempotent_consumer)

Garantir que processar a mesma mensagem duas vezes não cause efeito colateral. Usa PostgreSQL como tabela de controle com UNIQUE constraint + check duplo (SELECT + INSERT atômico).

**Portas:** Sender `8114` | Processor `8115` | PostgreSQL `5432`

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
