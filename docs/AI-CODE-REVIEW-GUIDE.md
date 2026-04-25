# AI CodeReview 使用指南

## 概述

本工具提供了一套标准化的AI CodeReview流程，通过精心设计的提示词模板，让AI能够：

- **全面评审**：覆盖代码质量、安全性、性能、并发、业务逻辑等多个维度
- **分类清晰**：将问题分为 Critical（必须修复）、Warning（建议修复）、Info（优化建议）三个级别
- **格式统一**：输出标准化的评审报告，便于团队使用和跟踪

## 文件说明

```
.
├── .cursor/rules/
│   └── alc-review.mdc              # Cursor Project Rules 配置文件
├── scripts/
│   ├── alc-review.sh               # Linux/Mac 自动脚本
│   └── alc-review.bat              # Windows 自动脚本
└── src/main/resources/prompts/
    └── alc-review-prompt.md        # AI CodeReview 提示词模板
```

## 使用方式

### 方式一：自动脚本（推荐）

#### Windows

```cmd
cd scripts
alc-review.bat              # 生成暂存区变更
alc-review.bat all         # 生成所有未合并到master的变更
alc-review.bat dev         # 基于dev分支生成diff
```

#### Linux / Mac

```bash
chmod +x scripts/alc-review.sh
./scripts/alc-review.sh              # 生成暂存区变更
./scripts/alc-review.sh --all       # 生成所有未合并到master的变更
./scripts/alc-review.sh --base dev  # 基于dev分支生成diff
```

### 方式二：手动复制diff

1. **生成diff文件**
   ```bash
   git diff master..HEAD > code_review.diff
   ```

2. **在Cursor中导入规则**
   - 打开 Cursor Settings > Project > Rules
   - 添加新规则，选择 `.cursor/rules/alc-review.mdc`

3. **进行CodeReview**
   - 在Cursor中新建对话
   - 输入：`使用 @{alc-review} 规则对以下diff进行CodeReview`
   - 粘贴diff内容

## 评审分类说明

### Critical（必须修复）
- 安全漏洞
- 严重性能问题
- 数据一致性问题
- 线程安全问题
- 空指针/崩溃风险

### Warning（建议修复）
- 代码质量问题
- 潜在性能隐患
- 可维护性问题
- 日志不规范

### Info（优化建议）
- 代码风格改进
- 命名优化
- 简化逻辑

## 输出报告示例

```markdown
# AI CodeReview 报告

## 评审概览
- **评审文件数**：3
- **Critical 问题数**：1
- **Warning 问题数**：2
- **Info 问题数**：1

---

## Critical 问题（必须修复）

| # | 文件路径 | 问题描述 | 影响 | 建议修复方案 |
|---|---------|---------|------|------------|
| 1 | UserService.java:45 | SQL注入风险 | 攻击者可构造恶意输入 | 使用预编译SQL或参数化查询 |

---

## Warning 问题（建议修复）

| # | 文件路径 | 问题描述 | 严重程度 | 建议修复方案 |
|---|---------|---------|---------|------------|
| 1 | OrderService.java:78 | N+1查询问题 | 数据库压力过大 | 使用批量查询或JOIN |

---

## Info 优化建议

| # | 文件路径 | 当前代码 | 优化建议 |
|---|---------|---------|---------|
| 1 | Utils.java:23 | getTmp | getTemperature 更清晰 |

---

## 总结
1. 重点关注UserService.sqlInject()方法的参数校验
2. OrderService建议使用批量查询优化性能
```

## 最佳实践

1. **提交前Review**：在提交代码前运行一次AI CodeReview
2. **优先级处理**：先处理Critical问题，再处理Warning，最后处理Info
3. **结合人工Review**：AI Review不能替代人工评审，仅作为辅助工具
4. **持续优化**：根据团队实际需求调整提示词模板