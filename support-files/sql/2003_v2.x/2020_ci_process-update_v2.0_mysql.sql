USE devops_ci_process;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ci_process_schema_update;

DELIMITER <CI_UBF>

CREATE PROCEDURE ci_process_schema_update()
BEGIN

    DECLARE db VARCHAR(100);
    SET AUTOCOMMIT = 0;
    SELECT DATABASE() INTO db;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_PIPELINE_BUILD_RECORD_TASK'
                    AND COLUMN_NAME = 'POST_INFO') THEN
    ALTER TABLE `T_PIPELINE_BUILD_RECORD_TASK`
        ADD COLUMN `POST_INFO` text DEFAULT NULL COMMENT '市场插件的POST关联信息';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_PIPELINE_PAUSE_VALUE'
                    AND COLUMN_NAME = 'EXECUTE_COUNT') THEN
    ALTER TABLE `T_PIPELINE_PAUSE_VALUE`
        ADD COLUMN `EXECUTE_COUNT` int(11) DEFAULT NULL COMMENT '执行次数';
    ALTER TABLE `T_PIPELINE_PAUSE_VALUE` DROP PRIMARY KEY;
    ALTER TABLE `T_PIPELINE_PAUSE_VALUE`
        ADD CONSTRAINT TASK_EXECUTE_COUNT UNIQUE (`PROJECT_ID`,`BUILD_ID`,`TASK_ID`,`EXECUTE_COUNT`);
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'SCOPE_TYPE') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `SCOPE_TYPE` varchar(32) NULL COMMENT '模板范围类型';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'STATUS') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `STATUS` varchar(32) NULL COMMENT '模板状态';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'TEMPLATE_YAML') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `TEMPLATE_YAML` mediumtext NULL COMMENT 'YAML 模板';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'DESC') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `DESC` varchar(1024) NULL COMMENT '描述';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'PIPELINE_VERSION') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `PIPELINE_VERSION` int(11) NULL DEFAULT 1 COMMENT '流水线模型版本';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'TRIGGER_VERSION') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `TRIGGER_VERSION` int(11) NULL DEFAULT 1 COMMENT '触发器模型版本';
    END IF;

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'SETTING_VERSION') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `SETTING_VERSION` int(11) NULL DEFAULT 1 COMMENT '关联的流水线设置版本号';
    END IF; 

    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_TEMPLATE'
                    AND COLUMN_NAME = 'REFS') THEN
    ALTER TABLE `T_TEMPLATE`
        ADD COLUMN `REFS` varchar(255) CHARACTER SET utf8mb4 NULL DEFAULT NULL COMMENT '来源代码库标识';
    END IF;        

    COMMIT;
END <CI_UBF>
DELIMITER ;
COMMIT;
CALL ci_process_schema_update();
