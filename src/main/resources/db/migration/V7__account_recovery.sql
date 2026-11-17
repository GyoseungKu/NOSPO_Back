ALTER TABLE auth_challenge ADD COLUMN withdrawal_operation_id BINARY(16);
ALTER TABLE auth_challenge ADD CONSTRAINT fk_challenge_withdrawal FOREIGN KEY (withdrawal_operation_id) REFERENCES withdrawal_operation(operation_id) ON DELETE RESTRICT;
ALTER TABLE auth_proof ADD COLUMN withdrawal_operation_id BINARY(16);
ALTER TABLE auth_proof ADD CONSTRAINT fk_proof_withdrawal FOREIGN KEY (withdrawal_operation_id) REFERENCES withdrawal_operation(operation_id) ON DELETE RESTRICT;
CREATE INDEX ix_recovery_challenge ON auth_challenge(withdrawal_operation_id,purpose);
CREATE INDEX ix_recovery_proof ON auth_proof(withdrawal_operation_id,purpose);
