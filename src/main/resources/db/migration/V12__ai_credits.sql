CREATE TABLE credit_operation (
    transaction_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor_id BINARY(16),
    request_key BINARY(16),
    job_id BINARY(16),
    created_at DATETIME(6) NOT NULL,
    CHECK (balance_after >= 0),
    CHECK ((kind = 'GRANT' AND amount > 0) OR (kind = 'RECOVERY' AND amount < 0)
        OR (kind = 'USE' AND amount = -1)),
    UNIQUE (member_id, actor_id, request_key),
    UNIQUE (job_id),
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_id) REFERENCES member(member_id) ON DELETE SET NULL
);

CREATE TABLE ai_conversation (
    conversation_id BINARY(16) PRIMARY KEY,
    member_id BINARY(16) NOT NULL,
    book_id BINARY(16) NOT NULL,
    position_unit VARCHAR(8) NOT NULL,
    position_value INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE INDEX ai_conversation_owner ON ai_conversation(member_id, created_at, conversation_id);

CREATE TABLE ai_question (
    question_id BINARY(16) PRIMARY KEY,
    job_id BINARY(16) NOT NULL UNIQUE,
    conversation_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    request_key BINARY(16),
    withdrawal_generation BIGINT NOT NULL,
    requested_conversation BINARY(16),
    requested_unit VARCHAR(8),
    requested_value INT,
    position_value INT NOT NULL,
    wiki_version INT NOT NULL,
    wiki_context TEXT,
    question TEXT NOT NULL,
    answer TEXT,
    citations TEXT,
    status VARCHAR(12) NOT NULL,
    error_code VARCHAR(40),
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    deadline DATETIME(6) NOT NULL,
    UNIQUE (member_id, request_key),
    FOREIGN KEY (conversation_id) REFERENCES ai_conversation(conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE CASCADE
);
CREATE INDEX ai_question_queue ON ai_question(status, deadline);
CREATE INDEX ai_question_conversation ON ai_question(conversation_id, created_at, question_id);

CREATE TABLE ai_question_delta (
    question_id BINARY(16) NOT NULL,
    sequence_number INT NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (question_id, sequence_number),
    FOREIGN KEY (question_id) REFERENCES ai_question(question_id) ON DELETE CASCADE
);
