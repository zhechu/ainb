# `licenses/` 目录说明

本目录存放**后端 `ainb` 产物里实际出现的第三方组件的协议全文**。
组件清单（哪个构件、哪个版本、什么协议、走哪条分支）在 [`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)。

> 口径：本目录**只覆盖本仓**。后端产物是一个 Java 可执行 jar（`enlev-server.jar`），
> 仓内**不含任何 JS / HTML / CSS 文件**（实测 `find . -name "*.js" -o -name "*.html" -o -name "*.css"`
> 排除 `target/` 后为 0 个）。Web 端与移动端的协议全文在各自仓库的 `licenses/` 目录，
> 三仓清单**内容不同是应当的**，不要互相对齐。

---

## 命名约定

| 形式 | 含义 |
| --- | --- |
| `<协议标识>-LICENSE` | 标准协议全文，供所有采用该协议的组件共用 |
| `<组件名>-LICENSE` | 该组件自带的**非标准 / 专有**条款全文，只对它自己有效 |

---

## 文件清单与来源

抓取日期 **2026-10-25**。来源分三类：**jar 内嵌**（最权威，直接取自本地 Maven 仓库
`C:\maven_repo` 里对应构件的 jar）、**官方 URL**（取自各构件 pom 的 `<licenses><url>`）、
**上游仓库 raw 文件**。

| 文件 | 协议 / 条款 | 来源 |
| --- | --- | --- |
| `Apache-2.0-LICENSE` | Apache License 2.0 | 官方文本 |
| `MIT-LICENSE` | MIT License | 官方文本 |
| `MIT-0-LICENSE` | MIT No Attribution | SPDX 官方文本 |
| `BSD-2-Clause-LICENSE` | BSD 2-Clause | SPDX 官方文本 |
| `BSD-3-Clause-LICENSE` | BSD 3-Clause | SPDX 官方文本 |
| `0BSD-LICENSE` | BSD Zero Clause | SPDX 官方文本 |
| `CC0-1.0-LICENSE` | Creative Commons CC0 1.0（公有领域） | SPDX 官方文本 |
| `BouncyCastle-LICENSE` | Bouncy Castle Licence（MIT 同构） | <https://www.bouncycastle.org/licence.html> |
| `MulanPSL-2.0-LICENSE` | 木兰宽松许可证 第 2 版 | <https://raw.githubusercontent.com/dromara/hutool/v5-master/LICENSE> |
| `UPL-1.0-LICENSE` | Universal Permissive License v1.0 | SPDX 官方文本 |
| `EDL-1.0-LICENSE` | Eclipse Distribution License v1.0 | **jar 内嵌**：`org.glassfish.jaxb:jaxb-core:4.0.9` 的 `META-INF/LICENSE.md` |
| `EPL-2.0-LICENSE` | Eclipse Public License v2.0 | **jar 内嵌**：`org.aspectj:aspectjweaver:1.9.25.1` 的 `licenses/LICENSE-EPL-2.0.txt` |
| `LGPL-2.1-LICENSE` | GNU LGPL v2.1 | <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt> |
| `LGPL-3.0-LICENSE` | GNU LGPL v3.0 | <https://www.gnu.org/licenses/lgpl-3.0.txt> |
| `GPL-2.0-LICENSE` | GNU GPL v2.0 | SPDX 官方文本（下面两个「GPL-2.0 + 例外」的基础文本） |
| `GPL-2.0-with-Classpath-Exception-LICENSE` | GPL v2.0 + Classpath Exception | **jar 内嵌**：`org.eclipse.angus:angus-mail:2.0.5` 的 `META-INF/LICENSE.md` 第 281-637 行（Eclipse 官方随包分发的合并文本） |
| `GPL-2.0-with-Universal-FOSS-Exception-LICENSE` | GPL v2.0 + Universal FOSS Exception v1.0 | GPL-2.0 官方文本 + <https://oss.oracle.com/licenses/universal-foss-exception/> |
| `GPL-3.0-LICENSE` | GNU GPL v3.0 | <https://www.gnu.org/licenses/gpl-3.0.txt>（**本项目不选此分支**，附全文备审） |
| `Oracle-FUTC-LICENSE` | Oracle Free Use Terms and Conditions | <https://www.oracle.com/downloads/licenses/oracle-free-license.html>（页面注明 Last updated: 9 June 2021） |
| `GraalVM-GFTC-LICENSE` | GraalVM Free Terms and Conditions（含 Early Adopter 条款） | <https://www.oracle.com/downloads/licenses/graal-free-license.html>（页面注明 Last updated: 12 June 2023） |
| `UnRar-LICENSE` | UnRar License | <https://raw.githubusercontent.com/junrar/junrar/master/LICENSE> |
| `Unicode-ICU-LICENSE` | Unicode/ICU License（含 ICU 的全部第三方归属） | **jar 内嵌**：`org.graalvm.shadowed:icu4j:24.1.2` 的 `LICENSE` |
| `Indiana-IUxSL-LICENSE` | Indiana University Extreme! Lab Software License | **jar 内嵌**：`io.github.x-stream:mxparser:1.2.2` 的 `META-INF/LICENSE` |
| `jdom2-LICENSE` | JDOM License（类 Apache，去掉致谢条款） | **jar 内嵌**：`org.jdom:jdom2:2.0.6.1` 的 `META-INF/LICENSE.txt` |
| `jsch-BSD-LICENSE` | JSch 主协议（Revised BSD） | **jar 内嵌**：`com.github.mwiede:jsch:2.28.3` 的 `META-INF/LICENSE.txt` |
| `jsch-JZlib-LICENSE` | JSch 内 JZlib 部分（zlib 同构） | **jar 内嵌**：同上 `META-INF/LICENSE.JZlib.txt` |
| `jsch-jBCrypt-LICENSE` | JSch 内 jBCrypt 部分（ISC 同构） | **jar 内嵌**：同上 `META-INF/LICENSE.jBCrypt.txt` |
| `jai-imageio-BSD-3-Clause-nuclear-LICENSE` | BSD 3-Clause **含核用途免责条款** | **jar 内嵌**：`com.github.jai-imageio:jai-imageio-core:1.4.0` 的 `META-INF/LICENSE` |
| `ruoyi-vue-pro-LICENSE` | MIT（© 2021 ruoyi-vue-pro） | <https://gitee.com/zhijiantianya/ruoyi-vue-pro/raw/master/LICENSE> |

### 未内置全文、只给 URL 的两项

| 协议 | 涉及构件 | 为什么没内置 | 本项目实际走的分支 |
| --- | --- | --- | --- |
| MPL-1.1 | `com.github.albfernandez:juniversalchardet:2.5.0`（三许可之一） | 官方站点只提供 HTML，无纯文本源；且本项目不选此分支 | **LGPL-3.0**（全文见 `LGPL-3.0-LICENSE`）。MPL-1.1 原文：<https://www.mozilla.org/en-US/MPL/1.1/> |
| CDDL-1.0 | `org.codelibs:jhighlight:1.1.1`（双许可之一） | 同上，`opensource.org/licenses/cddl1.txt` 返回 HTML 页面 | 双许可两条都属弱 copyleft，本清单同时列出。CDDL-1.0 原文：<http://www.opensource.org/licenses/cddl1.php>；LGPL-2.1 全文见 `LGPL-2.1-LICENSE` |

---

## 为什么这里没有前端组件的协议文本

本目录原先放着 `form-create-LICENSE`、`form-create-designer-LICENSE`、`yudao-ui-admin-vue3-LICENSE`
三份前端协议文本，**已删除**。理由是本仓根本不包含、也不分发这些代码：

- 本仓产出 `enlev-server.jar`，实测 `BOOT-INF/lib` 内 531 个 jar **全是 Java 构件**，无一例外；
- 本仓源码树里 `.js` / `.html` / `.css` / `.vue` / `.ts` 文件数为 **0**；
- form-create 的源码副本在 **Web 仓** `ainb-ui-admin-vue3/src/lib/`，移动端的 form-create
  规则模型是**独立实现**（在 `ainb-ui-admin-uniapp/src/pages-bpm/components/form-create/`），
  两者都不在本仓；
- 本仓的直接上游是 `ruoyi-vue-pro`（后端），**不是** `yudao-ui-admin-vue3`（前端）。

这三份文本分别保留在 Web 仓与移动端仓的 `licenses/` 目录里。删除记录在 git 历史中，需要时可回溯。

---

## 复现方式

协议取值**不靠记忆**，全部从本地 Maven 仓库里各构件的 pom 的 `<licenses>` 读出，
pom 没有该标签时向上递归 `<parent>`：

```bash
# 1. 导出产物实际用到的依赖坐标（compile + runtime，排除 provided / test）
cd ainb && mvn -o -q dependency:list -DincludeScope=runtime -DappendOutput=true -Dsort=true \
  -DoutputFile=../_logs/backend-deps.txt

# 2. 逐个读 C:\maven_repo 里的 pom，抽 <licenses>，归并成表
#    （解析脚本是一次性工具，不入库）

# 3. 与真正的产物对账——jar 才是权威，dependency:list 会把 pom 聚合器也算进来
unzip -Z1 enlev-server/target/enlev-server.jar "BOOT-INF/lib/*"
```
