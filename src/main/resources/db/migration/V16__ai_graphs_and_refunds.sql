CREATE TABLE ai_graph (
    job_id BINARY(16) PRIMARY KEY,
    graph_id BINARY(16) NOT NULL UNIQUE,
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    request_key BINARY(16),
    regenerate_from BINARY(16),
    requested_value INT,
    position_unit VARCHAR(8) NOT NULL,
    position_value INT NOT NULL,
    wiki_version INT NOT NULL,
    withdrawal_generation BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    payload TEXT,
    error_code VARCHAR(40),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    deadline DATETIME(6) NOT NULL,
    UNIQUE (member_id, request_key),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE INDEX ai_graph_queue ON ai_graph(status, deadline);
CREATE INDEX ai_graph_owner ON ai_graph(member_id, created_at, graph_id);

CREATE TABLE credit_refund (
    transaction_id BINARY(16) PRIMARY KEY,
    original_transaction_id BINARY(16) NOT NULL UNIQUE,
    actor_id BINARY(16),
    request_key BINARY(16) NOT NULL,
    amount INT NOT NULL CHECK (amount > 0),
    balance_after INT NOT NULL CHECK (balance_after >= 0),
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (original_transaction_id) REFERENCES credit_operation(transaction_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_id) REFERENCES member(member_id) ON DELETE SET NULL
);
