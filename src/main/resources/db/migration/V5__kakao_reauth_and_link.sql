-- Restore the ERD's one-context-per-flow constraint first. Existing duplicates must be
-- reviewed, never silently deleted; fail before adding flow columns when duplicates exist.
ALTER TABLE social_signup_context ADD CONSTRAINT uq_signup_context_flow UNIQUE (flow_id);
-- Preserve V4's split OAuth exchange storage; add the flow fields defined by the Auth ERD.
ALTER TABLE oauth_flow ADD COLUMN flow_type VARCHAR(24) NOT NULL DEFAULT 'LOGIN';
ALTER TABLE oauth_flow ADD COLUMN purpose VARCHAR(40);
ALTER TABLE oauth_flow ADD COLUMN reauth_proof_id BINARY(16);
ALTER TABLE oauth_flow ADD CONSTRAINT fk_oauth_reauth_proof
    FOREIGN KEY (reauth_proof_id) REFERENCES auth_proof(proof_id) ON DELETE RESTRICT;
