USE devops_ci_dispatch;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ci_dispatch_schema_update;

DELIMITER <CI_UBF>

CREATE PROCEDURE ci_dispatch_schema_update()
BEGIN

    DECLARE db VARCHAR(100);
    SET AUTOCOMMIT = 0;
    SELECT DATABASE() INTO db;

    IF EXISTS(SELECT 1
                   FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = db
                     AND TABLE_NAME = 'T_DISPATCH_THIRDPARTY_AGENT_BUILD'
                     AND INDEX_NAME = 'BUILD_ID') THEN
    ALTER TABLE `T_DISPATCH_THIRDPARTY_AGENT_BUILD`
            DROP INDEX BUILD_ID;
    END IF;

    IF NOT EXISTS(SELECT 1
                   FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = db
                     AND TABLE_NAME = 'T_DISPATCH_THIRDPARTY_AGENT_BUILD'
                     AND INDEX_NAME = 'IDX_BVE') THEN
    ALTER TABLE `T_DISPATCH_THIRDPARTY_AGENT_BUILD`
            ADD INDEX `IDX_BVE` (`BUILD_ID`, `VM_SEQ_ID`, `EXECUTE_COUNT`);
    END IF;

    IF NOT EXISTS(SELECT 1
                   FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = db
                     AND TABLE_NAME = 'T_DISPATCH_THIRDPARTY_AGENT_BUILD'
                     AND INDEX_NAME = 'IDX_ENV_ID') THEN
    ALTER TABLE `T_DISPATCH_THIRDPARTY_AGENT_BUILD`
            ADD INDEX `IDX_ENV_ID`(`ENV_ID`);
    END IF;

    -- Job 并发查询先按配额维度 + 活跃状态筛选，避免扫描整条流水线的历史记录。
    -- 新索引覆盖旧索引的最左前缀，先确保新索引创建成功，再删除旧索引。
    -- 用索引元数据判断，重复执行不重复创建或删除。
    IF NOT EXISTS(SELECT 1
                  FROM information_schema.statistics
                  WHERE TABLE_SCHEMA = db
                    AND TABLE_NAME = 'T_DISPATCH_THIRDPARTY_AGENT_BUILD'
                    AND INDEX_NAME = 'IDX_PROJECT_ENV_JOB_STATUS') THEN
    ALTER TABLE `T_DISPATCH_THIRDPARTY_AGENT_BUILD`
            ADD INDEX `IDX_PROJECT_ENV_JOB_STATUS` (`PROJECT_ID`, `ENV_ID`, `JOB_ID`, `PIPELINE_ID`, `STATUS`);
    END IF;

    IF EXISTS(SELECT 1
              FROM information_schema.statistics
              WHERE TABLE_SCHEMA = db
                AND TABLE_NAME = 'T_DISPATCH_THIRDPARTY_AGENT_BUILD'
                AND INDEX_NAME = 'IDX_PROJECT_ENV_JOB') THEN
    ALTER TABLE `T_DISPATCH_THIRDPARTY_AGENT_BUILD`
            DROP INDEX `IDX_PROJECT_ENV_JOB`;
    END IF;

    COMMIT;

END <CI_UBF>
DELIMITER ;
COMMIT;
CALL ci_dispatch_schema_update();

-- 第三方构建机构建记录的展示表，由 dispatch 根据 T_DISPATCH_THIRDPARTY_AGENT_BUILD 异步同步，只供节点/环境的构建记录查询使用。
-- 统计范围 SCOPE_KEY：A:构建机ID 或 E:环境范围（环境ID，所有创作环境为 环境ID:项目ID）。
CREATE TABLE IF NOT EXISTS `T_DISPATCH_TPA_HISTORY`
(
    `ID`            bigint(20)    NOT NULL COMMENT '与 T_DISPATCH_THIRDPARTY_AGENT_BUILD.ID 一致',
    `PROJECT_ID`    varchar(64)   NOT NULL COMMENT '项目ID',
    `AGENT_ID`      varchar(32)   NOT NULL COMMENT '构建机ID',
    `ENV_KEY`       varchar(96)   NULL COMMENT '环境范围：环境ID，所有创作环境为 环境ID:项目ID',
    `PIPELINE_ID`   varchar(34)   NOT NULL DEFAULT '' COMMENT '流水线ID',
    `PIPELINE_NAME` varchar(255)  NOT NULL DEFAULT '' COMMENT '流水线名称',
    `BUILD_ID`      varchar(34)   NOT NULL COMMENT '构建ID',
    `BUILD_NUM`     int(11)       NOT NULL DEFAULT 0 COMMENT '构建号',
    `EXECUTE_COUNT` int(11)       NOT NULL COMMENT '执行次数',
    `VM_SEQ_ID`     varchar(34)   NOT NULL COMMENT '构建序列号',
    `JOB_ID`        varchar(128)  NULL COMMENT 'Job ID',
    `TASK_NAME`     varchar(255)  NOT NULL DEFAULT '' COMMENT 'Job 名称',
    `STAGE_ID`      varchar(64)   NULL COMMENT 'Stage ID',
    `STATUS`        int(11)       NOT NULL COMMENT '状态',
    `LATEST`        tinyint(4)    NOT NULL DEFAULT 0 COMMENT '是否为同构建机、同构建、同 Job 的最新一行',
    `STATUS_LATEST` tinyint(4)    NOT NULL DEFAULT 0 COMMENT '是否为同构建机、同构建、同 Job、同状态的最新一行',
    `START_USER`    varchar(64)   NULL COMMENT '启动人',
    `WORKSPACE`     varchar(4096) NULL COMMENT '工作空间',
    `TIME_INTERVAL` bigint(20)    NULL COMMENT '耗时（秒）',
    `CREATED_TIME`  datetime      NOT NULL COMMENT '创建时间',
    `UPDATED_TIME`  datetime      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`ID`),
    KEY `IDX_BUILD` (`BUILD_ID`),
    KEY `IDX_AGENT_TIME` (`AGENT_ID`, `CREATED_TIME`),
    KEY `IDX_AGENT_STATUS` (`AGENT_ID`, `STATUS`, `STATUS_LATEST`),
    KEY `IDX_AGENT_PIPELINE_JOB` (`AGENT_ID`, `PIPELINE_ID`, `JOB_ID`),
    KEY `IDX_ENV_TIME` (`ENV_KEY`, `CREATED_TIME`),
    KEY `IDX_ENV_PIPELINE_JOB` (`ENV_KEY`, `PIPELINE_ID`, `JOB_ID`),
    KEY `IDX_STATUS` (`STATUS`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方构建机构建记录展示：明细';

CREATE TABLE IF NOT EXISTS `T_DISPATCH_TPA_HISTORY_BUILD`
(
    `SCOPE_KEY`     varchar(96)  NOT NULL COMMENT '统计范围',
    `BUILD_ID`      varchar(34)  NOT NULL COMMENT '构建ID',
    `EXECUTE_COUNT` int(11)      NOT NULL COMMENT '执行次数',
    `PROJECT_ID`    varchar(64)  NOT NULL COMMENT '项目ID',
    `PIPELINE_ID`   varchar(34)  NOT NULL COMMENT '流水线ID',
    `PIPELINE_NAME` varchar(255) NOT NULL DEFAULT '' COMMENT '流水线名称',
    `BUILD_NUM`     int(11)      NOT NULL DEFAULT 0 COMMENT '构建号',
    `START_USER`    varchar(64)  NULL COMMENT '启动人',
    `START_TIME`    datetime     NULL COMMENT '已结束 Job 中最早的开始时间',
    `TOTAL_TIME`    bigint(20)   NULL COMMENT '已结束 Job 执行区间合并后的总耗时（秒）',
    `LAST_ID`       bigint(20)   NOT NULL COMMENT '该构建在此范围内最新的明细ID',
    PRIMARY KEY (`SCOPE_KEY`, `BUILD_ID`, `EXECUTE_COUNT`),
    KEY `IDX_SCOPE_LAST` (`SCOPE_KEY`, `LAST_ID`),
    KEY `IDX_SCOPE_PIPELINE_LAST` (`SCOPE_KEY`, `PIPELINE_ID`, `LAST_ID`),
    KEY `IDX_SCOPE_USER` (`SCOPE_KEY`, `START_USER`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方构建机构建记录展示：构建视图';

CREATE TABLE IF NOT EXISTS `T_DISPATCH_TPA_HISTORY_PIPELINE`
(
    `SCOPE_KEY`     varchar(96)  NOT NULL COMMENT '统计范围',
    `PIPELINE_ID`   varchar(34)  NOT NULL COMMENT '流水线ID',
    `PROJECT_ID`    varchar(64)  NOT NULL COMMENT '项目ID',
    `PIPELINE_NAME` varchar(255) NOT NULL DEFAULT '' COMMENT '流水线名称',
    `BUILD_COUNT`   int(11)      NOT NULL DEFAULT 0 COMMENT '构建数，按构建ID和执行次数计',
    `LAST_BUILD_ID` varchar(34)  NOT NULL DEFAULT '' COMMENT '最新构建ID',
    `LAST_ID`       bigint(20)   NOT NULL COMMENT '最新的明细ID',
    PRIMARY KEY (`SCOPE_KEY`, `PIPELINE_ID`),
    KEY `IDX_SCOPE_LAST` (`SCOPE_KEY`, `LAST_ID`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方构建机构建记录展示：流水线视图';

CREATE TABLE IF NOT EXISTS `T_DISPATCH_TPA_HISTORY_JOB`
(
    `SCOPE_KEY`         varchar(96)  NOT NULL COMMENT '统计范围',
    `PIPELINE_ID`       varchar(34)  NOT NULL COMMENT '流水线ID',
    `JOB_ID`            varchar(128) NOT NULL COMMENT 'Job ID',
    `PROJECT_ID`        varchar(64)  NOT NULL COMMENT '项目ID',
    `PIPELINE_NAME`     varchar(255) NOT NULL DEFAULT '' COMMENT '流水线名称',
    `TASK_NAME`         varchar(255) NOT NULL DEFAULT '' COMMENT 'Job 名称',
    `RUN_COUNT`         int(11)      NOT NULL DEFAULT 0 COMMENT '执行次数',
    `TIME_SUM`          bigint(20)   NOT NULL DEFAULT 0 COMMENT '已结束执行的耗时之和（秒）',
    `TIME_COUNT`        int(11)      NOT NULL DEFAULT 0 COMMENT '计入耗时的执行次数',
    `LAST_CREATED_TIME` datetime     NULL COMMENT '最近一次执行的创建时间',
    `LAST_VM_SEQ_ID`    varchar(34)  NOT NULL DEFAULT '' COMMENT '最近一次执行的构建序列号',
    `LAST_STAGE_ID`     varchar(64)  NULL COMMENT '最近一次执行的 Stage ID',
    `LAST_ID`           bigint(20)   NOT NULL COMMENT '最新的明细ID',
    PRIMARY KEY (`SCOPE_KEY`, `PIPELINE_ID`, `JOB_ID`),
    KEY `IDX_SCOPE_LAST` (`SCOPE_KEY`, `LAST_ID`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方构建机构建记录展示：Job 视图';

CREATE TABLE IF NOT EXISTS `T_DISPATCH_TPA_HISTORY_SYNC`
(
    `SYNC_KEY`     varchar(64) NOT NULL COMMENT '进度名称',
    `PROGRESS`     bigint(20)  NOT NULL DEFAULT 0 COMMENT '进度值',
    `UPDATED_TIME` datetime    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`SYNC_KEY`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方构建机构建记录展示：同步进度';
