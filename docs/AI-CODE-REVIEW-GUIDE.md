# AI CodeReview 完整使用指南（中文版）

> 适用于：Trae IDE（中文版）、Cursor IDE

---

## 一、项目文件说明

你项目的文件清单：

```
sakura-agent-master/
├── .cursor/
│   └── rules/
│       └── alc-review.mdc              ← 规则文件
├── scripts/
│   ├── alc-review.bat                  ← 【重要】自动生成Diff脚本
│   ├── alc-review.sh                   ← Mac/Linux脚本
│   └── init-git.bat                   ← Git初始化脚本
├── src/main/resources/prompts/
│   └── alc-review-prompt.md            ← 【重要】AI提示词模板
└── docs/
    └── AI-CODE-REVIEW-GUIDE.md         ← 本教程
```

---

## 二、第一步：初始化Git仓库（仅需执行一次）

在项目根目录空白处，**按住 Shift + 右键**，选择"在此处打开 PowerShell 窗口"

运行：
```powershell
git init
git add .
git commit -m "Initial commit"
git branch -M master
```

---

## 三、第二步：在 Trae 中配置规则（仅需执行一次）

### 3.1 打开设置

1. 点击 Trae 左下角 **设置图标**（齿轮图标）
2. 或者按 **Ctrl + 逗号**（Ctrl + ,）

### 3.2 找到项目规则

1. 在搜索框中输入 **"规则"**
2. 点击 **"项目"**
3. 点击 **"规则"**

### 3.3 添加规则

1. 点击 **"添加规则"**
2. 规则名称填写：`alc-review`
3. 规则内容填写：

```markdown
@import "./src/main/resources/prompts/python_rule.md"
```

4. 点击 **"保存"**

---

## 四、第三步：修改代码

用 Trae 打开项目，随意改一个文件，比如添加一行注释

---

## 五、第四步：生成Diff文件

### 5.1 打开终端

在项目根目录，**按住 Shift + 右键**，选择"在此处打开 PowerShell 窗口"

### 5.2 运行脚本

```powershell
.\scripts\alc-review.bat
```

脚本运行后会自动输出diff内容和提示词

---

## 六、第五步：在 Trae 中触发规则进行CodeReview

### 6.1 新建对话

在 Trae 中新建一个聊天会话（按 `Ctrl + N`）

### 6.2 输入触发规则（重要！！！）

**在输入框中输入以下内容：**

```
使用 @{alc-review} 规则对以下diff进行CodeReview
```

### 6.3 粘贴Diff内容

1. 鼠标选中终端窗口
2. 按 `Ctrl + A` 全选
3. 按 `Ctrl + C` 复制
4. 回到 Trae 输入框，按 `Ctrl + V` 粘贴

### 6.4 发送

按 **回车键** 发送

### 6.5 等待AI输出评审报告

AI会按照规则输出格式化的CodeReview报告！

---

## 七、完整操作流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI CodeReview 操作流程                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  第一步：修改代码         用 Trae 打开项目，随意改一个文件           │
│        │                                                       │
│        ▼                                                       │
│  第二步：生成 Diff      终端运行 .\scripts\alc-review.bat       │
│        │                                                       │
│        ▼                                                       │
│  第三步：全选复制       终端按 Ctrl+A 全选，Ctrl+C 复制           │
│        │                                                       │
│        ▼                                                       │
│  第四步：粘贴到 Trae   Trae按 Ctrl+V 粘贴                       │
│        │                                                       │
│        ▼                                                       │
│  第五步：发送          输入 "使用 @{alc-review} 规则..." + 发送  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 八、关键：如何在Trae中触发规则

### 触发语法

```
使用 @{alc-review} 规则对以下diff进行CodeReview
```

### 完整输入示例

```
使用 @{alc-review} 规则对以下diff进行CodeReview

diff --git a/src/main/java/com/yupi/yuaiagent/app/LoveApp.java b/src/main/java/...
index abc123..def456 100644
--- a/src/main/java/com/yupi/yuaiagent/app/LoveApp.java
+++ b/src/main/java/com/yupi/yuaiagent/app/LoveApp.java
@@ -22,6 +22,7 @@
+// 我是测试注释
```

### 示意图

```
┌─────────────────────────────────────────────────────────────┐
│  Trae 对话窗口                                              │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                              │
│  用户输入:                                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 使用 @{alc-review} 规则对以下diff进行CodeReview        │   │
│  │                                                      │   │
│  │ diff --git a/src/main/java/...                       │   │
│  │ index abc123..def456 100644                         │   │
│  │ ...                                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                              │
│                                              [发送按钮]       │
└─────────────────────────────────────────────────────────────┘
```

---

## 九、快速参考

```powershell
# 1. 修改代码后，终端运行：
.\scripts\alc-review.bat

# 2. Trae 新建对话，输入：
使用 @{alc-review} 规则对以下diff进行CodeReview

# 3. Ctrl+A 全选终端内容，Ctrl+C 复制，Ctrl+V 粘贴到 Trae

# 4. 按回车发送
```

---

## 十、常见问题

### Q1: @{alc-review} 不起作用
- 确保规则名称是 `alc-review`，不是其他名字
- 确保规则内容是 `@import "./src/main/resources/prompts/alc-review-prompt.md"`

### Q2: Diff 文件为空
- 确保已初始化 Git 仓库
- 确保已修改文件但还没提交

### Q3: 找不到脚本
- 在终端中进入项目根目录：`cd G:\新建文件夹\sakura-agent-master`
- 然后运行：`.\scripts\alc-review.bat`

---

## 十一、文件位置汇总

| 文件 | 路径 |
|-----|------|
| 详细教程 | `docs/AI-CODE-REVIEW-GUIDE.md` |
| AI提示词 | `src/main/resources/prompts/alc-review-prompt.md` |
| 规则文件 | `.cursor/rules/alc-review.mdc` |
| Windows脚本 | `scripts/alc-review.bat` |

---

**现在你可以在 Trae 中配置并使用了！**