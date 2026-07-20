-- name: GetLoginCredentialByNormalizedIdentifier :one
SELECT i.subject_id,c.password_hash,c.credential_status
FROM identity_identifiers i JOIN identity_password_credentials c ON c.subject_id=i.subject_id
WHERE i.normalized_value=? AND i.status=? AND i.identifier_usage IN(?,?)
LIMIT 1;
