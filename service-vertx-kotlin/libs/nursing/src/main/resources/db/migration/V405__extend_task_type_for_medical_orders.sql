-- 医嘱派生护理任务类型扩展：既有五类 + MEDICATION/TREATMENT
ALTER TABLE nursing.nursing_tasks DROP CONSTRAINT nursing_tasks_task_type_check;
ALTER TABLE nursing.nursing_tasks ADD CONSTRAINT nursing_tasks_task_type_check
    CHECK (task_type IN ('NURSING', 'REHABILITATION', 'LIVING_CARE', 'HEALTH_EDUCATION', 'OTHER', 'MEDICATION', 'TREATMENT'));
