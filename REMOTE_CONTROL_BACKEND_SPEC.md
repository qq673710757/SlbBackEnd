# 远程启停CPU/GPU挖矿功能 - 后端对接文档

## 📋 需求概述

需要实现通过Web或App远程控制客户端启停CPU/GPU挖矿的功能。客户端会定期轮询设备详情接口获取待执行的控制指令，执行后回传确认结果。

---

## 🎯 实现方案

### 方案选择：基于现有接口扩展 + 新增控制接口

**核心思路**：
1. 扩展现有 `GET /api/v1/devices/{deviceId}` 接口，在返回的 `DeviceVo` 中增加 `remoteControl` 字段
2. 新增 `POST /api/v1/devices/{deviceId}/remote-control` 接口用于发送控制指令
3. 新增 `POST /api/v1/devices/{deviceId}/remote-control/ack` 接口用于客户端确认执行结果

**优势**：
- 复用现有轮询机制，客户端无需额外请求
- RESTful设计，资源路径清晰
- 向后兼容，不影响现有功能

---

## 📊 数据库设计

> **⚠️ 重要提示**：现有的 `devices` 表**无需任何修改**，所有远程控制相关数据存储在新表 `device_remote_commands` 中。两表通过 `user_id` 和 `device_id` 字段关联。

### 表关系说明

```
devices 表（现有表，保持不变）
├── id (主键)
├── user_id
├── device_name
├── device_id
├── cpu_hashrate
├── gpu_hashrate
└── ... 其他现有字段

device_remote_commands 表（新建表）
├── id (主键)
├── command_id (UUID)
├── user_id ────────┐
├── device_id ──────┼─→ 通过这两个字段关联到 devices 表
├── command_type    │
├── status          │
└── ... 其他字段    │
```

**为什么不修改 devices 表？**
1. **职责分离**：设备信息和控制指令是两个不同的业务领域
2. **历史记录**：独立表可以保留指令执行历史，便于审计和问题排查
3. **性能优化**：避免频繁更新 devices 表，减少锁竞争
4. **扩展性**：未来可以轻松添加更多指令类型，不影响设备表结构

### 新增表：`device_remote_commands`

```sql
CREATE TABLE `device_remote_commands` (
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
```

**字段说明**：
- `command_id`: 使用UUID生成，用于防止客户端重复执行
- `status`: 
  - `PENDING`: 待执行
  - `EXECUTED`: 已成功执行
  - `FAILED`: 执行失败
  - `EXPIRED`: 已过期（超过5分钟未执行）
- `expires_at`: 指令过期时间，建议设置为创建时间 + 5分钟

---

## 🔌 API接口规范

### 1. 扩展现有接口：获取设备详情

**接口地址**：`GET /api/v1/devices/{deviceId}`

**修改内容**：在返回的 `DeviceVo` 中新增 `remoteControl` 字段

#### 响应示例（新增部分）

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "deviceId": "device-123456",
    "deviceName": "My Mining Rig #1",
    "status": 1,
    "cpuHashrate": 1500.5,
    "gpuHashrate": 25000.0,
    // ... 其他现有字段 ...
    
    // ✅ 新增字段：远程控制状态（如果没有待执行指令则为 null）
    "remoteControl": {
      "cpuCommand": "start_cpu",      // CPU控制指令：start_cpu | stop_cpu | none
      "gpuCommand": "none",            // GPU控制指令：start_gpu | stop_gpu | none
      "commandId": "cmd-uuid-123456",  // 指令唯一ID
      "timestamp": 1737014400,         // 指令创建时间戳（秒）
      "expiresAt": 1737014700          // 指令过期时间戳（秒）
    }
  },
  "traceId": "b3f7e6c9a1d24c31"
}
```

#### 业务逻辑

```java
// 伪代码示例
public DeviceVo getDeviceDetail(Long userId, String deviceId) {
    DeviceVo device = deviceRepository.findByUserIdAndDeviceId(userId, deviceId);
    
    // ✅ 查询该设备是否有待执行的远程指令
    RemoteControlStatus remoteControl = getLatestPendingCommands(userId, deviceId);
    
    if (remoteControl != null && remoteControl.hasCommands()) {
        device.setRemoteControl(remoteControl);
    }
    
    return device;
}

private RemoteControlStatus getLatestPendingCommands(Long userId, String deviceId) {
    LocalDateTime now = LocalDateTime.now();
    
    // 查询最新的CPU指令（PENDING状态且未过期）
    DeviceRemoteCommand cpuCmd = commandRepository.findLatestPendingCommand(
        userId, deviceId, 
        Arrays.asList(CommandType.START_CPU, CommandType.STOP_CPU),
        now
    );
    
    // 查询最新的GPU指令（PENDING状态且未过期）
    DeviceRemoteCommand gpuCmd = commandRepository.findLatestPendingCommand(
        userId, deviceId,
        Arrays.asList(CommandType.START_GPU, CommandType.STOP_GPU),
        now
    );
    
    // 如果都没有待执行指令，返回 null
    if (cpuCmd == null && gpuCmd == null) {
        return null;
    }
    
    // 构造返回对象（CPU和GPU可能使用同一个commandId，也可能不同）
    RemoteControlStatus status = new RemoteControlStatus();
    
    if (cpuCmd != null) {
        status.setCpuCommand(cpuCmd.getCommandType().name().toLowerCase());
        status.setCommandId(cpuCmd.getCommandId());
        status.setTimestamp(cpuCmd.getCreatedAt().toEpochSecond(ZoneOffset.UTC));
        status.setExpiresAt(cpuCmd.getExpiresAt().toEpochSecond(ZoneOffset.UTC));
    }
    
    if (gpuCmd != null) {
        status.setGpuCommand(gpuCmd.getCommandType().name().toLowerCase());
        // 如果CPU和GPU指令不同，需要特殊处理（建议使用最新的commandId）
        if (cpuCmd == null || gpuCmd.getCreatedAt().isAfter(cpuCmd.getCreatedAt())) {
            status.setCommandId(gpuCmd.getCommandId());
            status.setTimestamp(gpuCmd.getCreatedAt().toEpochSecond(ZoneOffset.UTC));
            status.setExpiresAt(gpuCmd.getExpiresAt().toEpochSecond(ZoneOffset.UTC));
        }
    }
    
    return status;
}
```

#### 数据模型（Java）

```java
// DeviceVo.java 新增字段
@Data
public class DeviceVo {
    private String deviceId;
    private String deviceName;
    // ... 其他现有字段 ...
    
    // ✅ 新增字段
    private RemoteControlStatus remoteControl;
}

// RemoteControlStatus.java（新增类）
@Data
public class RemoteControlStatus {
    private String cpuCommand;   // start_cpu | stop_cpu | none
    private String gpuCommand;   // start_gpu | stop_gpu | none
    private String commandId;    // UUID
    private Long timestamp;      // 秒级时间戳
    private Long expiresAt;      // 秒级时间戳
    
    public boolean hasCommands() {
        return (cpuCommand != null && !cpuCommand.equals("none"))
            || (gpuCommand != null && !gpuCommand.equals("none"));
    }
}
```

---

### 2. 新增接口：发送远程控制指令

**接口地址**：`POST /api/v1/devices/{deviceId}/remote-control`

**接口说明**：Web或App端调用此接口向指定设备发送启停指令

#### 请求参数

**Path参数**：
- `deviceId` (string, required): 设备ID

**Header**：
- `Authorization` (string, required): Bearer {token}

**Body** (application/json):
```json
{
  "commandType": "start_cpu"  // 必填，可选值：start_cpu | stop_cpu | start_gpu | stop_gpu
}
```

#### 请求示例

```bash
curl -X POST "https://suanlibao.xyz/api/v1/devices/device-123456/remote-control" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "commandType": "start_cpu"
  }'
```

#### 响应示例

**成功响应** (200 OK):
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "commandId": "cmd-uuid-123456",
    "status": "pending",
    "expiresAt": 1737014700
  },
  "traceId": "b3f7e6c9a1d24c31"
}
```

**失败响应** (403 Forbidden):
```json
{
  "code": 403,
  "message": "无权操作此设备",
  "error": {
    "code": "DEVICE_ACCESS_DENIED",
    "displayMessage": "您没有权限控制此设备"
  },
  "traceId": "b3f7e6c9a1d24c31"
}
```

#### 业务逻辑

```java
@PostMapping("/{deviceId}/remote-control")
public ApiResponse<SendCommandResponse> sendRemoteControl(
        @PathVariable String deviceId,
        @RequestBody SendCommandRequest request,
        @RequestHeader("Authorization") String token) {
    
    Long userId = extractUserIdFromToken(token);
    
    // 1. 验证设备归属权限
    if (!deviceService.isDeviceOwnedByUser(deviceId, userId)) {
        throw new ForbiddenException("无权操作此设备");
    }
    
    // 2. 验证commandType合法性
    CommandType commandType;
    try {
        commandType = CommandType.valueOf(request.getCommandType().toUpperCase());
    } catch (IllegalArgumentException e) {
        throw new BadRequestException("无效的指令类型");
    }
    
    // 3. 将该设备同类型的旧指令标记为过期（避免冲突）
    commandRepository.expireOldCommands(deviceId, commandType);
    
    // 4. 创建新指令
    DeviceRemoteCommand command = new DeviceRemoteCommand();
    command.setCommandId(UUID.randomUUID().toString());
    command.setUserId(userId);
    command.setDeviceId(deviceId);
    command.setCommandType(commandType);
    command.setStatus(CommandStatus.PENDING);
    command.setCreatedAt(LocalDateTime.now());
    command.setExpiresAt(LocalDateTime.now().plusMinutes(5)); // 5分钟过期
    
    commandRepository.save(command);
    
    // 5. 返回结果
    SendCommandResponse response = new SendCommandResponse();
    response.setCommandId(command.getCommandId());
    response.setStatus("pending");
    response.setExpiresAt(command.getExpiresAt().toEpochSecond(ZoneOffset.UTC));
    
    return ApiResponse.success(response);
}
```

#### 数据模型（Java）

```java
// SendCommandRequest.java
@Data
public class SendCommandRequest {
    @NotBlank(message = "指令类型不能为空")
    private String commandType; // start_cpu | stop_cpu | start_gpu | stop_gpu
}

// SendCommandResponse.java
@Data
public class SendCommandResponse {
    private String commandId;
    private String status;
    private Long expiresAt;
}

// CommandType.java（枚举）
public enum CommandType {
    START_CPU,
    STOP_CPU,
    START_GPU,
    STOP_GPU
}

// CommandStatus.java（枚举）
public enum CommandStatus {
    PENDING,   // 待执行
    EXECUTED,  // 已执行
    FAILED,    // 执行失败
    EXPIRED    // 已过期
}
```

---

### 3. 新增接口：确认指令已执行

**接口地址**：`POST /api/v1/devices/{deviceId}/remote-control/ack`

**接口说明**：客户端执行完指令后调用此接口确认执行结果

#### 请求参数

**Path参数**：
- `deviceId` (string, required): 设备ID

**Header**：
- `Authorization` (string, required): Bearer {token}

**Body** (application/json):
```json
{
  "commandId": "cmd-uuid-123456",  // 必填，指令ID
  "success": true,                  // 必填，是否执行成功
  "error": null,                    // 可选，失败原因（仅在success=false时）
  "executedAt": 1737014450          // 必填，执行时间戳（秒）
}
```

#### 请求示例

**成功执行**：
```bash
curl -X POST "https://suanlibao.xyz/api/v1/devices/device-123456/remote-control/ack" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "commandId": "cmd-uuid-123456",
    "success": true,
    "executedAt": 1737014450
  }'
```

**执行失败**：
```bash
curl -X POST "https://suanlibao.xyz/api/v1/devices/device-123456/remote-control/ack" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "commandId": "cmd-uuid-123456",
    "success": false,
    "error": "GPU驱动未安装",
    "executedAt": 1737014450
  }'
```

#### 响应示例

**成功响应** (200 OK):
```json
{
  "code": 0,
  "message": "ok",
  "data": null,
  "traceId": "b3f7e6c9a1d24c31"
}
```

**失败响应** (404 Not Found):
```json
{
  "code": 404,
  "message": "指令不存在或已过期",
  "error": {
    "code": "COMMAND_NOT_FOUND",
    "displayMessage": "指令不存在或已过期"
  },
  "traceId": "b3f7e6c9a1d24c31"
}
```

#### 业务逻辑

```java
@PostMapping("/{deviceId}/remote-control/ack")
@Transactional
public ApiResponse<Void> ackRemoteControl(
        @PathVariable String deviceId,
        @RequestBody AckCommandRequest request,
        @RequestHeader("Authorization") String token) {
    
    Long userId = extractUserIdFromToken(token);
    
    // 1. 查询指令是否存在
    DeviceRemoteCommand command = commandRepository
        .findByCommandIdAndUserId(request.getCommandId(), userId)
        .orElseThrow(() -> new NotFoundException("指令不存在或已过期"));
    
    // 2. 验证设备ID匹配
    if (!command.getDeviceId().equals(deviceId)) {
        throw new BadRequestException("设备ID不匹配");
    }
    
    // 3. 验证指令状态（只能确认PENDING状态的指令）
    if (command.getStatus() != CommandStatus.PENDING) {
        throw new BadRequestException("指令已被处理，无法重复确认");
    }
    
    // 4. 更新指令状态
    command.setStatus(request.isSuccess() ? CommandStatus.EXECUTED : CommandStatus.FAILED);
    command.setExecutedAt(LocalDateTime.ofEpochSecond(request.getExecutedAt(), 0, ZoneOffset.UTC));
    command.setErrorMessage(request.getError());
    
    commandRepository.save(command);
    
    // 5. 可选：记录日志或发送通知
    logCommandExecution(command, request.isSuccess());
    
    return ApiResponse.success(null);
}
```

#### 数据模型（Java）

```java
// AckCommandRequest.java
@Data
public class AckCommandRequest {
    @NotBlank(message = "指令ID不能为空")
    private String commandId;
    
    @NotNull(message = "执行结果不能为空")
    private Boolean success;
    
    private String error; // 可选，失败原因
    
    @NotNull(message = "执行时间不能为空")
    private Long executedAt; // 秒级时间戳
}
```

---

## 🗄️ Repository层实现

```java
@Repository
public interface DeviceRemoteCommandRepository 
        extends JpaRepository<DeviceRemoteCommand, Long> {
    
    /**
     * 查询最新的待执行指令（按创建时间倒序）
     */
    @Query("SELECT c FROM DeviceRemoteCommand c " +
           "WHERE c.userId = :userId " +
           "AND c.deviceId = :deviceId " +
           "AND c.commandType IN :types " +
           "AND c.status = 'PENDING' " +
           "AND c.expiresAt > :now " +
           "ORDER BY c.createdAt DESC")
    DeviceRemoteCommand findLatestPendingCommand(
        @Param("userId") Long userId,
        @Param("deviceId") String deviceId,
        @Param("types") List<CommandType> types,
        @Param("now") LocalDateTime now
    );
    
    /**
     * 将旧指令标记为过期（避免冲突）
     */
    @Modifying
    @Query("UPDATE DeviceRemoteCommand c " +
           "SET c.status = 'EXPIRED' " +
           "WHERE c.deviceId = :deviceId " +
           "AND c.commandType = :type " +
           "AND c.status = 'PENDING'")
    void expireOldCommands(
        @Param("deviceId") String deviceId,
        @Param("type") CommandType type
    );
    
    /**
     * 根据commandId和userId查询指令
     */
    Optional<DeviceRemoteCommand> findByCommandIdAndUserId(
        String commandId, Long userId
    );
    
    /**
     * 定时任务：清理过期指令（建议保留7天历史记录）
     */
    @Modifying
    @Query("UPDATE DeviceRemoteCommand c " +
           "SET c.status = 'EXPIRED' " +
           "WHERE c.status = 'PENDING' " +
           "AND c.expiresAt < :now")
    int expireTimeoutCommands(@Param("now") LocalDateTime now);
    
    /**
     * 定时任务：删除7天前的历史记录
     */
    @Modifying
    @Query("DELETE FROM DeviceRemoteCommand c " +
           "WHERE c.createdAt < :threshold")
    int deleteOldRecords(@Param("threshold") LocalDateTime threshold);
}
```

---

## ⏰ 定时任务（可选但推荐）

```java
@Component
public class RemoteCommandCleanupTask {
    
    @Autowired
    private DeviceRemoteCommandRepository commandRepository;
    
    /**
     * 每5分钟执行一次：将超时未执行的指令标记为EXPIRED
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional
    public void expireTimeoutCommands() {
        int count = commandRepository.expireTimeoutCommands(LocalDateTime.now());
        if (count > 0) {
            log.info("标记了 {} 条超时指令为EXPIRED", count);
        }
    }
    
    /**
     * 每天凌晨3点执行：删除7天前的历史记录
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void deleteOldRecords() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int count = commandRepository.deleteOldRecords(threshold);
        if (count > 0) {
            log.info("删除了 {} 条历史指令记录", count);
        }
    }
}
```

---

## 🔒 安全性考虑

### 1. 权限验证
- 发送指令时必须验证设备归属关系（`isDeviceOwnedByUser`）
- 确认执行时验证 `userId` 和 `deviceId` 匹配

### 2. 防重放攻击
- 使用UUID作为 `commandId`，确保唯一性
- 客户端记录已执行的 `commandId`，防止重复执行
- 指令设置5分钟过期时间

### 3. 并发控制
- 同一设备同一类型的指令，新指令会将旧指令标记为 `EXPIRED`
- 确认执行时检查指令状态，防止重复确认

### 4. 数据清理
- 定时任务清理过期指令，避免数据库膨胀
- 保留7天历史记录用于审计和问题排查

---

## 📈 性能优化建议

### 1. 数据库索引
```sql
-- 核心查询索引
CREATE INDEX idx_device_status ON device_remote_commands(device_id, status);
CREATE INDEX idx_user_device ON device_remote_commands(user_id, device_id);
CREATE INDEX idx_expires ON device_remote_commands(expires_at, status);
```

### 2. 缓存优化（可选）
```java
// 使用Redis缓存待执行指令，减少数据库查询
@Cacheable(value = "remote_commands", key = "#deviceId")
public RemoteControlStatus getPendingCommands(Long userId, String deviceId) {
    // ... 查询逻辑 ...
}

// 发送新指令时清除缓存
@CacheEvict(value = "remote_commands", key = "#deviceId")
public String createCommand(Long userId, String deviceId, CommandType type) {
    // ... 创建逻辑 ...
}
```

### 3. 批量查询优化
如果需要查询多个设备的指令状态，可以提供批量接口：
```java
@GetMapping("/remote-control/batch")
public ApiResponse<Map<String, RemoteControlStatus>> batchGetCommands(
        @RequestParam List<String> deviceIds,
        @RequestHeader("Authorization") String token) {
    // ... 批量查询逻辑 ...
}
```

---

## 🧪 测试用例

### 1. 发送指令测试
```java
@Test
public void testSendRemoteControl() {
    // 1. 正常发送指令
    SendCommandRequest request = new SendCommandRequest();
    request.setCommandType("start_cpu");
    
    ApiResponse<SendCommandResponse> response = sendRemoteControl(
        "device-123", request, "Bearer valid-token"
    );
    
    assertEquals(0, response.getCode());
    assertNotNull(response.getData().getCommandId());
    
    // 2. 无权限设备
    assertThrows(ForbiddenException.class, () -> {
        sendRemoteControl("other-device", request, "Bearer valid-token");
    });
    
    // 3. 无效指令类型
    request.setCommandType("invalid_command");
    assertThrows(BadRequestException.class, () -> {
        sendRemoteControl("device-123", request, "Bearer valid-token");
    });
}
```

### 2. 获取指令测试
```java
@Test
public void testGetDeviceWithRemoteControl() {
    // 1. 有待执行指令
    createCommand(userId, deviceId, CommandType.START_CPU);
    
    DeviceVo device = getDeviceDetail(userId, deviceId);
    assertNotNull(device.getRemoteControl());
    assertEquals("start_cpu", device.getRemoteControl().getCpuCommand());
    
    // 2. 无待执行指令
    DeviceVo device2 = getDeviceDetail(userId, "other-device");
    assertNull(device2.getRemoteControl());
}
```

### 3. 确认执行测试
```java
@Test
public void testAckRemoteControl() {
    // 1. 成功执行
    String commandId = createCommand(userId, deviceId, CommandType.START_CPU);
    
    AckCommandRequest ackRequest = new AckCommandRequest();
    ackRequest.setCommandId(commandId);
    ackRequest.setSuccess(true);
    ackRequest.setExecutedAt(System.currentTimeMillis() / 1000);
    
    ApiResponse<Void> response = ackRemoteControl(
        deviceId, ackRequest, "Bearer valid-token"
    );
    
    assertEquals(0, response.getCode());
    
    // 验证状态已更新
    DeviceRemoteCommand command = commandRepository
        .findByCommandIdAndUserId(commandId, userId).get();
    assertEquals(CommandStatus.EXECUTED, command.getStatus());
    
    // 2. 重复确认（应该失败）
    assertThrows(BadRequestException.class, () -> {
        ackRemoteControl(deviceId, ackRequest, "Bearer valid-token");
    });
}
```

---

## 📝 客户端轮询机制说明

客户端会通过以下方式获取并执行远程指令：

1. **轮询频率**：客户端已有设备详情轮询（约60秒），无需额外请求
2. **执行流程**：
   ```
   客户端轮询 → 获取 remoteControl 字段 → 检查 commandId 是否已执行
   → 执行启停操作 → 调用 ack 接口确认结果
   ```
3. **防重复执行**：客户端维护已执行的 `commandId` 集合，避免重复执行
4. **超时处理**：指令5分钟未执行自动过期，客户端忽略过期指令

---

## 🚀 上线检查清单

- [ ] 数据库表 `device_remote_commands` 已创建
- [ ] 索引已创建（`idx_device_status`, `idx_user_device`, `idx_expires`）
- [ ] `DeviceVo` 已添加 `remoteControl` 字段
- [ ] 三个接口已实现并测试通过
- [ ] Repository层查询方法已实现
- [ ] 定时任务已配置（过期指令清理、历史记录清理）
- [ ] 权限验证逻辑已实现
- [ ] 单元测试已通过
- [ ] 接口文档已更新（Swagger/Apifox）
- [ ] 日志记录已添加（指令创建、执行、失败）

---

## 📞 联系方式

如有疑问，请联系客户端开发团队：
- 技术负责人：[你的名字]
- 邮箱：[你的邮箱]
- 企业微信/钉钉：[你的联系方式]

---

## 📅 版本历史

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-02-16 | 初始版本 | 客户端团队 |


