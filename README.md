# 震相合议室

基于 Java 17、Spring Boot、SQLite 和原生浏览器资源的离线多台站 P/S 震相拾取复核应用。

## 构建与启动

```bash
./gradlew build -x test
./gradlew test
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=5234'
```

打开 http://127.0.0.1:5234 ，页面标题为“震相合议室”。SQLite 默认写入当前目录的 `phase-room.db`，可用 `PHASE_DB_PATH` 覆盖。

## 数据模型

- 台站坐标、速度模型、波形片段和拾取均以内容哈希形成不可变版本。
- 同一片段按内容哈希去重，但 `waveform_receptions` 会累计每次导入和来源。
- 解释分支记录当前台站版本、拾取版本、模型 ID、台站校时修正和完整可撤销事件历史。
- 冻结时保存自描述快照，同时钉住模型、台站和拾取版本；之后修改台站海拔不会影响冻结结论。
- 冻结分支不可编辑，可复制为新分支并替换速度模型。

## 主要接口

- `POST /api/events`：创建事件。
- `POST /api/stations/versions`：创建台站版本。
- `POST /api/velocity-models/versions`：创建分层 P/S 速度模型。
- `POST /api/waveforms`：导入波形、缺口、重叠片段和削顶样本索引。
- `POST /api/events/{code}/picks`：创建候选拾取。
- `POST /api/branches`：创建解释分支并固定版本组合。
- `POST /api/branches/{code}/actions`：移动拾取、改极性/置信区间、合并、判噪声、校时或换模型。
- `POST /api/branches/{code}/undo`：撤销最后一个动作。
- `GET/POST /api/branches/{code}/compare`：按台站输出预测到时、观测到时、预测差、观测差、残差和权重来源。
- `POST /api/branches/{code}/replay`：用历史模型、台站和拾取版本重放计算。
- `GET /api/branches/{code}/conflicts`：查看校时导致先后次序反转时的最小冲突台站集合。
- `POST /api/branches/{code}/freeze`：冻结；发生次序反转会返回 409 和冲突台站。
- `POST /api/branches/{code}/clone`：复制冻结或开放分支，并可替换模型。

## 确定性规则

模型比较仅允许所有选中候选都在指定窗口内的模型获胜，越窗模型即使平均残差更小也只标记为不合格。合格模型按加权 RMS、模型 ID 稳定排序；同分时返回确定性的并列模型 ID。冻结冲突在同一震相内比较预测序、原始观测序和校时后观测序，再用顶点覆盖求字典序最小的冲突台站集合。
