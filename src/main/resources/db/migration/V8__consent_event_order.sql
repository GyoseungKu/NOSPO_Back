-- Keep every consent event; establish a stable per-member order for existing rows.
ALTER TABLE member_consent ADD COLUMN event_order BIGINT NOT NULL DEFAULT 0;
CREATE TABLE consent_order_migration AS
SELECT consent_id, ROW_NUMBER() OVER (PARTITION BY member_id ORDER BY agreed_at, consent_id) AS event_order
FROM member_consent;
UPDATE member_consent SET event_order =
    (SELECT event_order FROM consent_order_migration r WHERE r.consent_id=member_consent.consent_id);
DROP TABLE consent_order_migration;
CREATE UNIQUE INDEX ux_consent_member_order ON member_consent(member_id,event_order);
CREATE INDEX ix_consent_member_version_order ON member_consent(member_id,term_version_id,event_order);
CREATE INDEX ix_consent_member_history ON member_consent(member_id,agreed_at,consent_id);
