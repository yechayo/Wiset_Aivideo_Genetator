SELECT e.id,
  JSON_UNQUOTE(JSON_EXTRACT(e.episode_info, '$.gridStatus')) as gridStatus,
  JSON_UNQUOTE(JSON_EXTRACT(e.episode_info, '$.panelApproved')) as panelApproved
FROM episode e
WHERE e.project_id = 'PROJ-eac66e22'
  AND JSON_EXTRACT(e.episode_info, '$.gridStatus') = '"approved"'
  AND (JSON_EXTRACT(e.episode_info, '$.panelApproved') IS NULL OR JSON_EXTRACT(e.episode_info, '$.panelApproved') != '"true"');
