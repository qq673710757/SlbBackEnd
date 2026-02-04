## 接口：收益历史（按小时汇总）

- **Method**: `GET`
- **Path**: `/api/v1/earnings/history-hourly`
- **Auth**: 需要登录（`Authorization: Bearer <token>`）
- **用途**: 将 `earnings_history` 明细按小时聚合，支持按收益类型分组（一次返回 CPU/GPU/INVITE/... 多条）。

### Query 参数

- **page**: 页码（从 1 开始），默认 `1`
- **size**: 每页条数，默认 `10`
- **deviceId**: 设备/标识过滤（对应 `earnings_history.device_id`），可选
- **startDate**: 开始日期（`yyyy-MM-dd`），可选
- **endDate**: 结束日期（`yyyy-MM-dd`），可选
- **earningType**: 收益类型过滤（不区分大小写），可选
  - 支持：`CPU` / `GPU` / `INVITE` / `INVITED` / `COMPENSATION` / `INCENTIVE` / `SYSTEM_INCENTIVE`
  - 说明：历史数据可能存在 `INVITE_CPU` / `INVITE_GPU`（邀请返佣的旧拆分口径）
  - 不传或传 `ALL`：不过滤
- **groupBy**: 聚合维度，可选
  - 不传或传 `hour`：按“小时”聚合（每小时 1 条）
  - 传 `earningType`：按“小时 + earningType”聚合（同一小时多条，一次拿全类型）

### 返回字段（list[]）

- **earningTime**: 小时桶起始时间（HH:00:00）
- **earningType**:
  - `groupBy=hour`：不传 `earningType` 时为 `ALL`；传了则回显传入值
  - `groupBy=earningType`：为真实类型（如 `CPU/GPU/INVITE/...`）
- **amountCal**: 汇总的 CAL 金额
- **amountCny**: 汇总的 CNY 金额
- **recordCount**: 该聚合桶合并的明细条数
- **settleCurrency**:
  - 聚合桶内币种一致：返回 `CAL` 或 `CNY`
  - 聚合桶内混合：返回 `MIXED`
  - 说明：币种来自明细记录的推断（关联 `platform_commissions.currency`；无记录时默认按 `CAL`）

### 示例

#### 1）按小时聚合（全部类型）

`/api/v1/earnings/history-hourly?page=1&size=10`

#### 2）按小时 + 类型聚合（一次拿到各类型）

`/api/v1/earnings/history-hourly?page=1&size=50&groupBy=earningType`

#### 3）只看返佣（INVITE）

`/api/v1/earnings/history-hourly?page=1&size=50&earningType=INVITE`


