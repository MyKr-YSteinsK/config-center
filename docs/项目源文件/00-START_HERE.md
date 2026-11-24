# config-center 项目交接

本批资料采用 `AI-Workflow-Assets` 的工程项目孵化器，来源版本为 [750c696](https://github.com/MyKr-YSteinsK/ai-workflow-assets/tree/750c69673ccaeac9b5999f3b38b8ebc11abebc24/项目孵化器)。接管基于现有 `MyKr-YSteinsK/config-center` 的 `master`，以 Java 后端简历价值为长期目标。首轮单仓维护已按 [Config-Plan03](https://github.com/MyKr-YSteinsK/config-center/issues/2) 交付。

## 创建 GPT Project

1. 建立项目，名称可用 **config-center｜Java 后端与可靠变更**。
2. 把 [PROJECT_INSTRUCTIONS.md](PROJECT_INSTRUCTIONS.md) 全文粘贴到项目指令。
3. 上传 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) 与 [UPGRADE_ROADMAP.md](UPGRADE_ROADMAP.md)。从仓库的同一提交取得两份文件，把这一提交 SHA 放在新对话的第一条消息里。
4. 可用下面的启动消息核对 GPT 实际读取的内容。新快照生效后，再处理可能冲突的同名旧附件。

> 接手 MyKr-YSteinsK/config-center，资料版本为 <commit SHA>。先根据 PROJECT_CONTEXT.md 和 UPGRADE_ROADMAP.md 说明当前可运行基线、已验证证据和待审升级。请说明 Config-Plan03 的本地和远端证据，再提出下一轮 Java CLI 与 Watch 故障恢复的可观察验收，等我确认该轮范围。

## 指令与维护源

| 文件 | 使用位置与维护方式 |
| --- | --- |
| [PROJECT_INSTRUCTIONS.md](PROJECT_INSTRUCTIONS.md) | GPT Project 粘贴文本，读取约定下列两份附件，也可从 GitHub 核对最新事实。 |
| [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) | GPT 上传用的自包含接管快照。持久维护依据 [目标](../project/PROJECT_BRIEF.md)、[决定](../project/DECISIONS.md)、[状态](../project/CURRENT_STATE.md) 和 [运行手册](../project/RUNBOOK.md)。需要新附件时按对应提交更新这份快照。 |
| [UPGRADE_ROADMAP.md](UPGRADE_ROADMAP.md) | 完整审查和待确认的升级路线，可直接上传。用户选定后，再将已接受的决定写入现行记录。 |
| [CODEX_GLOBAL_AGENTS.md](CODEX_GLOBAL_AGENTS.md) | 从工作流资产复制的 Codex 全局 AGENTS 候选。按需对照个人 Codex 配置，实际项目入口使用仓库根的 [AGENTS.md](../../AGENTS.md)。 |
| [GPT_GLOBAL_CUSTOM_INSTRUCTIONS.txt](GPT_GLOBAL_CUSTOM_INSTRUCTIONS.txt) | 工作流资产中的 ChatGPT 账号级自定义指令候选。GPT Project 的启动只需要项目指令和两份项目附件。 |

以上文件已落到既有仓库。Codex 新对话从仓库根 [AGENTS.md](../../AGENTS.md) 进入，项目源文件可直接手动同步到 GPT。正式任务正文优先使用获授权的目标仓库 Issue；受限内容使用能读取的私有渠道。首次接管来自用户对话，后续 Config-Plan03 已通过本仓库 Issue #2 交接。待审产品范围进入正式 Bootstrap ZIP 以及后续实施前，由用户确认。
