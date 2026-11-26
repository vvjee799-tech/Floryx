# 手机文件夹工作区

设置入口：「侧边栏 → 设置 → **工作区**」。通过 Android 系统文件夹选择器（Storage Access Framework, SAF）选择手机上的已有文件夹并授权访问。

## 核心设计

- **按会话绑定与默认模板**：每个会话可独立关联工作区；开启「新会话默认使用」后，新建会话自动继承该目录，已有会话不受影响。取消关联仅移除设置，不会删除手机原文件。
- **SAF 与本地执行目录隔离**：手机目录通过 Android SAF 文档 URI 访问；如需使用 Linux 命令行脚本处理，则按需将显式指定的 `workspace_inputs` 导入到会话专属的 `/workspace/phone/<workspace-key>` 目录，处理完成后通过 `workspace_outputs` 或 `workspace_publish` 写回原文件夹。
- **内容校验与冲突保护**：写回文件时核对原文件 SHA-256 修订值。若原文件在操作期间被外部应用修改或删除，Floryx 会保留原文件并生成带标识的冲突副本，同时在界面和工具返回中明确提示实际保存位置。写回失败的成果会保留在本地「待保存」列表，支持后续重试保存。

## 大文件有界读取与结构化检查

为避免大文件超出内存或模型上下文窗口，工作区工具提供原生的有界流式能力（不要求导入 Linux 环境或运行 Shell）：

- **元信息与结构预扫（`workspace_stat`）**：读取大小、MIME、弱版本标识与头部预览，对大文件做有界扫描（默认上限 8 MiB；非 seek 数据源 64 KiB），提供物理行结构、声明线索与尾段/搜索推荐参数。
- **字节范围读取（`workspace_read`）**：支持 `byte_offset` / `max_bytes` 字节模式（单次最多 64 KiB 且返回不超过 12,000 字符或 512 hex 字节），保证 UTF-8 多字节字符边界完整拼接并准确返回 `next_byte_offset`；同时保留旧有字符分页模式。
- **有界流式搜索（`workspace_search`）**：在不执行脚本的前提下进行大小写敏感字面扫描（单次默认 1 MiB、最高 8 MiB、最多 8 条命中），返回准确扫描范围、字节命中偏移、前后上下文与续扫偏移，并配合循环停滞守卫防止无效重复全文件搜索。

## 模型工具列表

| 工具 | 权限类型 | 用途 |
| --- | --- | --- |
| `workspace_status` | 只读 | 检查当前关联文件夹、读写状态与待保存记录 |
| `workspace_list` | 只读 | 分页浏览指定子目录（每次最多 50 项） |
| `workspace_stat` | 只读 | 查看文件大小、版本与有界结构/声明线索 |
| `workspace_read` | 只读 | 按字节范围或字符页读取文本/二进制摘要 |
| `workspace_search` | 只读 | 对文件内容执行有界流式字面搜索 |
| `workspace_write` | 写入 | 写入 UTF-8 文本并校验 SHA-256（最大 256 KiB） |
| `workspace_rename` | 写入 | 校验修订值后重命名，绝不覆盖同名文件 |
| `workspace_import` | 执行（需开启命令） | 将指定手机文件复制到会话 Linux 执行目录（单文件 ≤ 20 MiB，单批 ≤ 64 MiB） |
| `workspace_publish` | 执行（需开启命令） | 将本地执行结果写回原 SAF 文件夹并校验 |
| `workspace_inspect_execution` | 只读（需开启命令） | 查看或有界读取会话 Linux 执行副本，不触发命令 |
| `workspace_answer_compatibility` | 辅助汇总 | 汇总静态文件事实、手机事实与未验证未知项，输出带范围结论 |

## 本地存储与迁移

- Room 数据库 `conversations` 表通过 `MIGRATION_12_13` 存储可空的 `workspaceJson` 关联信息。
- 会话本地执行目录位于应用私有目录 `files/terminal/workspaces/<conversation-id>`，待保存传输状态保存在 `files/workspace-transfers`。
