# 表单契约的同步约定

本目录下有**两份**契约，同步方式完全一样。

## 两份文件

### 一、表单控件契约

| 角色 | 路径 |
|---|---|
| **规范副本**（改这份） | `ainb/enlev-module-infra/src/main/resources/form-design/form-widget-contracts.json` |
| 构建期副本 | `ainb-ui-admin-vue3/src/components/FormWidgets/contracts/form-widget-contracts.json` |

### 二、列格式化契约

| 角色 | 路径 |
|---|---|
| **规范副本**（改这份） | `ainb/enlev-module-infra/src/main/resources/form-design/cell-format-contracts.json` |
| 构建期副本 | `ainb-ui-admin-vue3/src/components/FormWidgets/contracts/cell-format-contracts.json` |

它声明「列表怎么显示、导出怎么导」。前端的 `listCell.ts` 与后端的
`ExportCellRenderer` 共用这一份 —— 各写各的话，前端改了某种格式化的行为、
导出那边不知道，表现是**列表显示正常、导出是另一套**，而界面上完全看不出来。

对应测试：
- 后端：`mvn -pl enlev-module-infra test -Dtest=CellFormatContractRegistryTest`
- 前端：⚠️ **没有**。本仓不存在 `tests/` 与 `playwright.config.ts`，`pnpm test:e2e` 跑不起来，
  vitest 里也没有覆盖这两份契约的 spec —— 前端副本目前只靠下面流程里的手工复制来对齐。

两个仓库是独立的 git 仓库，没有共享的构建产物，因此契约以「一份规范 + 一份副本 + 一致性测试」的方式落地。

## 改契约的流程

1. 改后端的规范副本。
2. 原样复制到前端：
   ```bash
   cp ainb/enlev-module-infra/src/main/resources/form-design/form-widget-contracts.json \
      ainb-ui-admin-vue3/src/components/FormWidgets/contracts/form-widget-contracts.json
   ```
3. 跑后端测试：`mvn -pl enlev-module-infra test -Dtest=WidgetContractRegistryTest`
   （前端那侧没有对应的一致性测试可跑，见上文 ⚠️）
4. 两个仓库分别提交。

## 为什么不做成运行时拉取

前端在模块加载期就要用 `subTableWidgetType` 这类结构性事实——多个工具模块在顶层就依赖它。
改成异步会把一批同步函数染成异步，牵动面远超收益。

折中是「本地副本 + 一致性测试」：运行时零网络依赖。⚠️ 但这个一致性测试今天**只有后端一侧**，
前端副本漂移没有任何自动化拦截，别以为 CI 会兜住。
`GET /infra/form-design/widget-contracts` 接口保留着，就是给（将来补上的）前端比对当基准。

## 一致性测试拦得住什么，拦不住什么

**拦得住**：后端规范副本自身坏了（字段缺失、取值越界）—— 那两条 Registry 测试守的是这个。

**拦不住**：前端副本与后端规范副本对不上。本来该由前端那条 e2e 比对来拦，但它在本仓不存在，
所以「后端改了、忘了复制」今天**不会有任何测试变红**。

**也拦不住**：两侧同时被改成不同的内容（各自都自洽）。这两种情况都只能靠流程约束——
永远先改后端规范副本，再复制到前端，不要在前端直接编辑 JSON，并且复制完自己 diff 一遍。

## structural.subFieldSeparator 的消费方（已于台账 #101(b) 收口）

这个字段还有一个消费方在别的模块里：`enlev-module-bpm` 的
`BpmTaskServiceImpl.SUB_FIELD_SEPARATOR`（流程节点的字段权限裁剪）。

它<b>不再是独立副本</b>：现在引用本模块的
`api/formdesign/dto/FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR`——Java 侧只有那一份常量，
并由 `SubFieldSeparatorSingleSourceTest` 把它钉到本 JSON 上（不是恒真断言：一侧是运行时解析出来的
JSON 值，一侧是编译期常量）。当初写在旧版本文档里的理由「跨模块不互相依赖内部实现」是错的：
bpm 的 pom 依赖 infra，被引的常量也确实在 api 包里。

⚠️ 但编译期常量会被<b>内联</b>进引用方的 class（JLS 15.28）。只重装 infra 的 jar 而不重装 bpm，
两侧就会静默漂移，而且单测看不见——全量 `mvn install` 后再重启。这也是
`GET /infra/form-design/widget-contracts` 与 `DiagnoseNotWorkingTool#diagnoseSubFieldSeparator`
这两个运行时出口要保留的原因：它们比的是「JSON 里的值」与「bpm 字节码里内联的值」。

两边对不上的表现仍是「子表字段配了权限却不生效」，或反过来把有权限的字段裁掉——
从现象很难反推到分隔符不一致。
