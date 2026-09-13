-- Editing a key is a configuration change in the FR-AUD-003 sense, exactly
-- as issuing or revoking one already is. It matters more than it looks:
-- contact_email decides who hears about a key's traffic, so changing it can
-- quietly redirect a volume alert away from the person who should see it.
-- An unaudited change of that kind is precisely what an audit log is for.
--
-- V12 pinned the action set with a CHECK, so extending the enum means
-- replacing that constraint.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'USER_INVITED', 'USER_ROLE_CHANGED', 'USER_DISABLED', 'USER_ENABLED',
    'CONVERSATION_ASSIGNED', 'CONVERSATION_UNASSIGNED', 'WHATSAPP_CONNECTED',
    'API_KEY_CREATED', 'API_KEY_UPDATED', 'API_KEY_DEACTIVATED', 'API_KEY_REACTIVATED'
));
