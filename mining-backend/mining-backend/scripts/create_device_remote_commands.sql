-- 远程控制指令表
CREATE TABLE IF NOT EXISTS `device_remote_commands` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  `command_id` VARCHAR(64) UNIQUE NOT NULL COMMENT '指令唯一ID（UUID）',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `device_id` VARCHAR(128) NOT NULL COMMENT '设备ID',
  `command_type` ENUM('START_CPU', 'STOP_CPU', 'START_GPU', 'STOP_GPU') NOT NULL COMMENT '指令类型',
  `status` ENUM('PENDING', 'EXECUTED', 'FAILED', 'EXPIRED') DEFAULT 'PENDING' COMMENT '指令状态',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `executed_at` TIMESTAMP NULL COMMENT '执行时间',
  `expires_at` TIMESTAMP NOT NULL COMMENT '过期时间（建议5分钟）',
  `error_message` TEXT NULL COMMENT '执行失败的错误信息',
  
  INDEX `idx_device_status` (`device_id`, `status`),
  INDEX `idx_user_device` (`user_id`, `device_id`),
  INDEX `idx_expires` (`expires_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备远程控制指令表';

