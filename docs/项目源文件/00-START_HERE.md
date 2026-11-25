# config-center｜接手与同步

项目已进入单仓 Java 后端迭代。Codex 从仓库根入口恢复，GPT Project Main 从同一提交的项目指令和两份附件恢复。先核实已交付的实包证据，再决定是否安排低基数诊断或固定数据集性能核验。[Codex 入口](../../AGENTS.md) · [当前状态](../project/CURRENT_STATE.md) · [待审优先级](UPGRADE_ROADMAP.md#待审优先级)

Config-Plan10 采用工程孵化器 `f50e2b1682af21842a730f20c8533cbc3372a72f` 的交接规则，沿用现有目录和任务编号。工作流源提供 01–06 Markdown 供孵化使用，现有仓库通过自己的项目文件继续维护。[孵化器源](https://github.com/MyKr-YSteinsK/ai-workflow-assets/tree/f50e2b1682af21842a730f20c8533cbc3372a72f/项目孵化器) · [落地约定](https://github.com/MyKr-YSteinsK/ai-workflow-assets/blob/f50e2b1682af21842a730f20c8533cbc3372a72f/项目孵化器/03-BOOTSTRAP_CONTRACT.md)

## Codex 接手

从根入口定位目标、当前状态和按需合同。普通任务直接使用当前对话。正式交接使用获授权的 Issue 完整正文，核对基线、范围、必要验收与 D-011。Issue 读不到时可用用户提供的完整正文或获授权的可读私有文件。[开发入口](../../AGENTS.md) · [交接与恢复决定](../project/DECISIONS.md#交接与恢复)

## 手动同步 GPT Project

1. 选定实际交付 SHA。在 GitHub 打开该提交中的本目录，使用同一个 SHA 取得下面三份文件。
2. 把项目指令全文粘贴到 GPT Project 的项目指令。[PROJECT_INSTRUCTIONS.md](PROJECT_INSTRUCTIONS.md)
3. 上传接手快照与升级路线两份 Markdown。[PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) · [UPGRADE_ROADMAP.md](UPGRADE_ROADMAP.md)
4. 开一个新对话，发送下方接手消息并核对它实际读到的资料。应能报出资料 SHA、最近已验收的 Plan09 运行证据以及下一步待审方向。若仍读到 2026-11-23 或 `0258b9c` 的初期资料，或无法确认两份附件同版，先核对文件来源。新版读通后再处理可能冲突的旧附件。

> 接手 MyKr-YSteinsK/config-center，资料提交为 <实际交付 SHA>。请核对同提交的项目指令、PROJECT_CONTEXT.md 和 UPGRADE_ROADMAP.md。先用一段通俗话说明 Plan09 验证了什么，再给出实际读到的版本、运行证据提交和待审优先级。遇到旧附件或读不到的来源请指出。若要比较低基数诊断与性能基线，请先说明哪个问题值得解决、什么最小证据会改变选择，再由我决定下一轮范围。

以仓库的实际交付提交为同步来源。GPT Project 的指令与附件由所有者手动更新，以新对话读到的内容确认生效。项目事实、长期决定和运行方法分别从现行文档核对。[目标](../project/PROJECT_BRIEF.md) · [当前状态](../project/CURRENT_STATE.md) · [决定](../project/DECISIONS.md) · [运行手册](../project/RUNBOOK.md)

## 可选全局候选

以下两份与上述工作流提交同源。Codex 候选用于按需对照个人全局规则，ChatGPT 候选用于按需对照账号级自定义指令。实际生效取决于用户安装或粘贴。项目接手使用根入口、项目指令和两份项目附件。

- [CODEX_GLOBAL_AGENTS.md](CODEX_GLOBAL_AGENTS.md) · [上游 Codex 全局 AGENTS](https://github.com/MyKr-YSteinsK/ai-workflow-assets/blob/f50e2b1682af21842a730f20c8533cbc3372a72f/Codex全局AGENTS/AGENTS.md)
- [GPT_GLOBAL_CUSTOM_INSTRUCTIONS.txt](GPT_GLOBAL_CUSTOM_INSTRUCTIONS.txt) · [上游 ChatGPT 自定义指令](https://github.com/MyKr-YSteinsK/ai-workflow-assets/blob/f50e2b1682af21842a730f20c8533cbc3372a72f/GPT自定义信息/GPT网页版-ChatGPT%20自定义指令.txt)
