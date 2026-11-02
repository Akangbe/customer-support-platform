-- A client opening one message sees that recipient's full phone number, while
-- lists show it masked. Each such view is recorded so that access to personal
-- data can be answered for afterwards.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'USER_INVITED', 'USER_ROLE_CHANGED', 'USER_DISABLED', 'USER_ENABLED',
    'CONVERSATION_ASSIGNED', 'CONVERSATION_UNASSIGNED', 'WHATSAPP_CONNECTED',
    'API_KEY_CREATED', 'API_KEY_UPDATED', 'API_KEY_DEACTIVATED', 'API_KEY_REACTIVATED',
    'CLIENT_RECIPIENT_VIEWED'
));
