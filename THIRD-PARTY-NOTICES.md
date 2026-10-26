# Third Party Notices · 第三方开源软件声明

> 本文件是**后端仓 `ainb` 的**第三方软件清单，只对本仓负责。
> 本仓产出一个 Java 可执行 jar（`enlev-server.jar`），仓内**不含任何前端代码**——
> 实测排除 `target/` 后 `.js` / `.html` / `.css` / `.vue` / `.ts` 文件数为 **0**。
> Web 端与移动端的第三方清单在各自仓库里，见 §8。
>
> 统计日期 **2026-10-25**，全部数值实测，复现方式见 §10。
> 协议全文见 [`licenses/`](./licenses)（该目录另有 `README.md` 说明每份文本的来源）。

---

## 1. 本仓库的派生关系

| 关系 | 上游 | 协议 | 全文 |
| --- | --- | --- | --- |
| **直接上游**（本仓由它整体改名而来） | [ruoyi-vue-pro](https://gitee.com/zhijiantianya/ruoyi-vue-pro) · 芋道源码 | MIT（© 2021 ruoyi-vue-pro） | [`licenses/ruoyi-vue-pro-LICENSE`](./licenses/ruoyi-vue-pro-LICENSE) |

改名口径：包名 `cn.iocoder.yudao` → `cn.enlev.ainb`，构件前缀 `yudao-` → `enlev-`。
仓库里残留的上游文档与 `yudao` 字样属历史遗留，不改变上述归属。

本仓**不是**从 `yudao-ui-admin-vue3`（前端）派生的，因此本目录不含它的协议文本。

### 与同产品另外两仓的关系

| 仓库 | 技术栈 | 直接上游 | 第三方清单 |
| --- | --- | --- | --- |
| **`ainb`（本仓）** | Java 17 + Spring Boot 3.5 + Maven 多模块 | `ruoyi-vue-pro`（MIT） | 本文件 |
| [`ainb-ui-admin-vue3`](https://gitee.com/enlev/ainb-ui-admin-vue3) | Vue 3.5 + Vite + Element Plus | `yudao-ui-admin-vue3`（MIT） | 该仓 `THIRD-PARTY-NOTICES.md` |
| [`ainb-ui-admin-uniapp`](https://gitee.com/enlev/ainb-ui-admin-uniapp) | Vue 3.4 + uni-app 3.0 + wot-ui | `yudao-ui-admin-uniapp`（MIT）+ `unibest`（MIT，© 2025 菲鸽） | 该仓 `THIRD-PARTY-NOTICES.md` |

三仓共同构成「启凌智搭」，但**产物互不包含**：后端 jar 里没有一个字节来自两个前端仓，反之亦然。

---

## 2. 产物构成（实测）

对 `enlev-server/target/enlev-server.jar`（构建于 2026-10-24，341,007,744 字节）实测：

| 项 | 数值 | 说明 |
| --- | --- | --- |
| `BOOT-INF/lib` 内 jar 总数 | **531** | 合计 340,668,320 字节 ≈ 325 MB |
| 其中本项目自身模块 | **19** | `cn.enlev.ainb:enlev-*`，见 §9，**不是第三方软件** |
| 其中第三方 jar | **512** | 本清单覆盖的对象 |
| Maven 解析出的第三方坐标 | **528** | `dependency:list -DincludeScope=runtime`（compile + runtime，排除 provided / test） |

「512 个 jar」与「528 个坐标」不等，原因是双向的，两边都已核实：

- **有坐标、无 jar（24 项）**：15 个 `spring-boot-starter-*`、2 个 `flowable-spring-boot-starter-*`、
  2 个 `org.graalvm{.polyglot,.js}:js` 都是 `<type>pom</type>` 聚合依赖，本身不产出 jar；
  `mapstruct-processor`、`spring-boot-configuration-processor` 两个注解处理器**未进入**
  `BOOT-INF/lib`（实测为 0）；3 个 netty 平台原生坐标只以 classifier 变体形式落盘。
- **有 jar、坐标清单里没有（9 项）**：8 个 netty 平台 classifier 变体
  （`netty-tcnative-boringssl-static` 的 linux-x86_64 / linux-aarch_64 / osx-x86_64 / osx-aarch_64 / windows-x86_64，
  `netty-transport-native-epoll-linux-x86_64`、`netty-transport-native-kqueue-osx-x86_64`、
  `netty-resolver-dns-native-macos-osx-x86_64`）+ 1 个由 `spring-boot-maven-plugin` 注入的
  `spring-boot-jarmode-tools-3.5.15.jar`。这两类构件的协议与其主坐标一致（netty 系 Apache-2.0，
  jarmode-tools 属 Spring Boot，Apache-2.0）。

依赖作用域分布：**compile 499、runtime 28、同时出现于两种作用域 1**。

---

## 3. 协议分布（实测，528 个坐标）

协议字段逐个从本地 Maven 仓库里各构件的 pom `<licenses>` 读出，pom 无该标签时向上递归 `<parent>`。

### 3.1 单协议（510 项）

| 协议 | 项数 | 全文 | 代表构件 |
| --- | --- | --- | --- |
| Apache-2.0 | **427** | [`Apache-2.0-LICENSE`](./licenses/Apache-2.0-LICENSE) | Spring Boot 3.5.15、Spring AI 1.1.8、Flowable 8.0.0、MyBatis-Plus 3.5.16、Redisson 4.6.1、Tika 3.3.1、POI 5.4.1/5.5.1、Netty 4.2.15 |
| MIT | **26** | [`MIT-LICENSE`](./licenses/MIT-LICENSE) | lombok 1.18.46、slf4j、snakeyaml 之外的若干工具库 |
| BSD（2-Clause / 3-Clause / 变体） | **16** | [`BSD-3-Clause-LICENSE`](./licenses/BSD-3-Clause-LICENSE)、[`BSD-2-Clause-LICENSE`](./licenses/BSD-2-Clause-LICENSE) | protobuf-java 3.25.8、antlr4-runtime 4.13.1、asm 9.10、xstream 1.4.21、dom4j 2.1.3、kryo 5.6.2 |
| UPL-1.0 | **10** | [`UPL-1.0-LICENSE`](./licenses/UPL-1.0-LICENSE) | GraalVM 24.1.2 的 `polyglot` / `truffle-api` / `truffle-compiler` / `truffle-runtime` / `regex` / `sdk:*` |
| EDL-1.0 | **7** | [`EDL-1.0-LICENSE`](./licenses/EDL-1.0-LICENSE) | `jaxb-core` / `jaxb-runtime` / `txw2` 4.0.9、`istack-commons-runtime` 4.1.2、`jakarta.activation-api` 2.1.4、`angus-activation` 2.0.3、`jakarta.xml.bind-api` 4.0.5 |
| Bouncy Castle Licence（MIT 同构） | **4** | [`BouncyCastle-LICENSE`](./licenses/BouncyCastle-LICENSE) | `bcprov` / `bcpkix` / `bcutil` 1.80、`bcjmail` 1.84 |
| 公有领域（Public Domain / CC0） | **3** | [`CC0-1.0-LICENSE`](./licenses/CC0-1.0-LICENSE) | `org.json:json` 20250107、`xmlpull` 1.1.3.1、`LatencyUtils` 2.0.3 |
| LGPL-2.1 / LGPL-3.0 | **3** | [`LGPL-2.1-LICENSE`](./licenses/LGPL-2.1-LICENSE)、[`LGPL-3.0-LICENSE`](./licenses/LGPL-3.0-LICENSE) | ⚠️ 见 §5.2 |
| **Oracle FUTC（专有免费条款）** | **3** | [`Oracle-FUTC-LICENSE`](./licenses/Oracle-FUTC-LICENSE) | 🔴 见 §5.1 |
| EPL-2.0 | **1** | [`EPL-2.0-LICENSE`](./licenses/EPL-2.0-LICENSE) | `aspectjweaver` 1.9.25.1，见 §5.2 |
| **GraalVM GFTC（专有免费条款）** | **1** | [`GraalVM-GFTC-LICENSE`](./licenses/GraalVM-GFTC-LICENSE) | 🔴 `truffle-enterprise` 24.1.2，见 §5.1 |
| **GPL-2.0 + Universal FOSS Exception** | **1** | [`GPL-2.0-with-Universal-FOSS-Exception-LICENSE`](./licenses/GPL-2.0-with-Universal-FOSS-Exception-LICENSE) | 🔴 `mysql-connector-j` 9.7.0，见 §5.2 |
| **UnRar License** | **1** | [`UnRar-LICENSE`](./licenses/UnRar-LICENSE) | 🔴 `junrar` 7.6.0，见 §5.1 |
| MulanPSL-2.0（木兰宽松许可证 第 2 版） | **1** | [`MulanPSL-2.0-LICENSE`](./licenses/MulanPSL-2.0-LICENSE) | `cn.hutool:hutool-all` 5.8.46，见 §5.7 |
| Unicode/ICU License | **1** | [`Unicode-ICU-LICENSE`](./licenses/Unicode-ICU-LICENSE) | `org.graalvm.shadowed:icu4j` 24.1.2（宽松，含 ICU 全部第三方归属） |
| Indiana University Extreme! Lab Software License | **1** | [`Indiana-IUxSL-LICENSE`](./licenses/Indiana-IUxSL-LICENSE) | `io.github.x-stream:mxparser` 1.2.2（BSD 同构） |
| JDOM License（类 Apache，去掉致谢条款） | **1** | [`jdom2-LICENSE`](./licenses/jdom2-LICENSE) | `org.jdom:jdom2` 2.0.6.1 |
| MIT-0 | **1** | [`MIT-0-LICENSE`](./licenses/MIT-0-LICENSE) | `org.reactivestreams:reactive-streams` 1.0.4 |
| 0BSD | **1** | [`0BSD-LICENSE`](./licenses/0BSD-LICENSE) | `org.tukaani:xz` 1.12 |
| **协议未声明** | **1** | — | 🔴 `net.jcip:jcip-annotations` 1.0，见 §5.4 |

> `org.graalvm.shadowed:icu4j` 的协议名 `Unicode/ICU License` 字面含斜杠，容易被误判成双许可，
> 实为**单一**协议，本表按单协议计。

### 3.2 多协议任选其一（18 项）

这类构件的 pom 声明了多个协议、使用者可任选其一。**本项目一律选其中最宽松的分支**，
选择结果见 §5.3。

| 协议组合 | 项数 | 构件 |
| --- | --- | --- |
| LGPL-2.1+ **或** Apache-2.0 | 3 | `jsqlparser` 5.2、`jna` 5.13.0、`jna-platform` 5.17.0 |
| EPL-2.0 **或** GPL-2.0 w/ CPE **或** EDL-1.0 | 3 | `jakarta.mail-api` 2.1.5、`angus-mail` 2.0.5、`org.eclipse.angus:jakarta.mail` 2.0.5 |
| EPL-2.0 **或** LGPL-2.1 | 2 | `logback-classic` 1.5.34、`logback-core` 1.5.34 |
| **GFTC** **或** MIT **或** UPL-1.0 | 2 | `org.graalvm.js:js` 24.1.2（pom）、`org.graalvm.polyglot:js` 24.1.2（pom） |
| UPL-1.0 **或** MIT | 2 | `js-community` 24.1.2、`js-language` 24.1.2 |
| EPL-2.0 **或** GPL-2.0 w/ CPE | 1 | `jakarta.annotation-api` 2.1.1 |
| CDDL-1.0 **或** LGPL-2.1+ | 1 | `jhighlight` 1.1.1 |
| MPL-1.1 **或** GPL-3.0 **或** LGPL-3.0 | 1 | `juniversalchardet` 2.5.0 |
| BSD-3-Clause（含核用途免责条款）**或** 标准 BSD | 1 | `jai-imageio-core` 1.4.0，全文 [`jai-imageio-BSD-3-Clause-nuclear-LICENSE`](./licenses/jai-imageio-BSD-3-Clause-nuclear-LICENSE) |
| Revised BSD × 2 **或** ISC | 1 | `jsch` 2.28.3，三份全文都在 `licenses/jsch-*` |
| 公有领域（CC0）**或** BSD-2-Clause | 1 | `HdrHistogram` 2.2.2 |

---

## 4. 主要组件与版本（按功能域）

版本全部取自实测解析结果，不是文档抄写。

| 功能域 | 组件（版本） | 协议 |
| --- | --- | --- |
| 应用框架 | Spring Boot **3.5.15**、Spring Framework 6.2.19、Tomcat Embed 10.1.55 | Apache-2.0 |
| 持久层 | MyBatis 3.5.19、MyBatis-Spring 3.0.5、MyBatis-Plus **3.5.16**、HikariCP 6.3.3、`dynamic-datasource-spring-boot3-starter` 4.5.0、Druid 1.2.28、jsqlparser 5.2 | Apache-2.0（jsqlparser 走 Apache 分支） |
| 数据库驱动 | `mysql-connector-j` **9.7.0** | 🔴 GPL-2.0 + Universal FOSS Exception |
| 缓存 | Redisson **4.6.1**、Caffeine 3.2.4 | Apache-2.0 |
| 工作流 | Flowable **8.0.0**、Groovy 4.0.32 | Apache-2.0 |
| 定时任务 | Quartz 2.5.2 | Apache-2.0 |
| AI | Spring AI **1.1.8**、Milvus SDK 2.5.8、Azure Identity 1.18.2 | Apache-2.0 |
| 脚本引擎 | GraalVM **24.1.2**（`polyglot` / `js` / `truffle-*` / `sdk:*`）、`icu4j` 24.1.2 | UPL-1.0 / MIT；🔴 `truffle-enterprise` 仅 GFTC |
| 文档解析 | Tika **3.3.1**（`tika-parsers-standard-package` 全家桶）、POI 5.4.1 与 5.5.1、xmlbeans 5.3.0、PDFBox 3.0.7、juniversalchardet 2.5.0、`jai-imageio-core` 1.4.0 | Apache-2.0 / BSD；⚠️ POI 版本分裂见 §5.5 |
| Excel | `cn.idev.excel:fastexcel` **1.3.0**、`fastexcel-support` 0.0.1 | Apache-2.0 |
| 对象存储 | AWS SDK v2 **2.46.17**（S3）、MinIO 客户端 | Apache-2.0 |
| 安全 / 加密 | Bouncy Castle `bcprov` 1.80 / `bcjmail` 1.84、jsch 2.28.3、`netty-tcnative-boringssl-static` 2.0.77 | Bouncy Castle Licence（MIT 同构）/ BSD / ISC；Apache-2.0 |
| 三方登录 | JustAuth **1.16.7**、`justauth-spring-boot-starter` 1.4.0、`simple-http` 1.0.5 | 🔴 LGPL-3.0，见 §5.2 |
| 微信生态 | `com.github.binarywang` 全家 **5 个构件**，版本一律 `4.8.4-20260623.211820`（`wx-java-mp-spring-boot-starter`、`wx-java-miniapp-spring-boot-starter`、`weixin-java-mp`、`weixin-java-miniapp`、`weixin-java-common`） | Apache-2.0；⚠️ 版本是带时间戳的 SNAPSHOT，见 §5.6 |
| 工具库 | Hutool `hutool-all` **5.8.46** 与 `org.dromara.hutool:*` **6.0.0-M22**、Guava 33.6.0-jre、commons-lang3 3.20.0、commons-io 2.21.0、commons-compress 1.27.1、jsoup 1.22.2、ip2region 2.7.0、`bizlog-sdk` 3.0.6 | ⚠️ 见 §5.7（木兰宽松许可证 / Apache-2.0） |
| 日志 | Logback **1.5.34**、SLF4J、Micrometer 1.15.12 | Logback 走 EPL-2.0 分支，见 §5.3 |
| API 文档 | springdoc-openapi **2.8.17**、knife4j 4.5.0、swagger-ui 5.32.2 | Apache-2.0 |
| 序列化 / 映射 | Jackson 2.21.4、MapStruct 1.6.3、Kryo 5.6.2、xstream 1.4.21、jdom2 2.0.6.1 | Apache-2.0 / MIT / BSD / JDOM License |
| 压缩 / 归档 | commons-compress 1.27.1、`xz` 1.12、junrar **7.6.0** | Apache-2.0 / 0BSD；🔴 junrar 见 §5.1 |
| 编译期（⚠️ 部分误入产物） | lombok **1.18.46**、`mapstruct-jdk8` 1.6.3 | MIT；见 §5.6 |

---

## 5. 🔴 非宽松协议 / 需自行确认再分发

**这一节是本清单的重点。** 上面 500 多项 Apache/MIT/BSD 组件只需保留版权声明；
下面这些不一样——它们要么不是开源协议，要么带 copyleft，要么根本没写协议。
把本项目的 jar 交给别人之前，请逐条读完。

### 5.1 专有免费条款（**不是开源协议**，5 项）

「免费可用」不等于「开源」。下面四项条款都不符合 OSI 定义，再分发前必须自行阅读并确认。

| 构件 | 版本 | 条款 | 全文 | 大小 |
| --- | --- | --- | --- | --- |
| `com.oracle.database.jdbc:ojdbc11` | 23.7.0.25.01 | Oracle Free Use Terms and Conditions | [`Oracle-FUTC-LICENSE`](./licenses/Oracle-FUTC-LICENSE) | 7.3 MB |
| `com.oracle.database.jdbc:ucp` | 23.7.0.25.01 | 同上 | 同上 | 1.5 MB |
| `com.oracle.database.spring:oracle-spring-boot-starter-ucp` | 23.4.0 | 同上 | 同上 | 3 KB |
| `org.graalvm.truffle:truffle-enterprise` | 24.1.2 | GraalVM Free Terms and Conditions（含 Early Adopter 版本条款） | [`GraalVM-GFTC-LICENSE`](./licenses/GraalVM-GFTC-LICENSE) | 634 KB |
| `com.github.junrar:junrar` | 7.6.0 | UnRar License（含 RAR 压缩算法的使用限制条款） | [`UnRar-LICENSE`](./licenses/UnRar-LICENSE) | 165 KB |

🔴 **Oracle 三件（合计 8.8 MB）本项目根本用不到。** 实测来源链：

```
enlev-server
└─ enlev-module-system
   └─ enlev-spring-boot-starter-mybatis
      └─ com.baomidou:dynamic-datasource-spring-boot3-starter:4.5.0
         └─ dynamic-datasource-spring-boot-common:4.5.0
            └─ oracle-spring-boot-starter-ucp:23.4.0
               ├─ ojdbc11:23.7.0.25.01
               └─ ucp:23.7.0.25.01
```

本项目**不支持 Oracle**：全仓没有一处 Oracle JDBC 的 import（唯一的 `oracle` 字样命中是
`IdTypeEnvironmentPostProcessor.java:90` 里的字符串常量
`"org.quartz.impl.jdbcjobstore.oracle.OracleDelegate"`，属 Quartz 的方言名，不引用 Oracle 类），
`DataSourceEnum` 只定义了 `MASTER` / `SLAVE`。更能说明问题的是：
`enlev-spring-boot-starter-mybatis/pom.xml:36-40` **已经把 `ojdbc8` 显式声明成
`<optional>true</optional>`**——项目本来就没打算带 Oracle 驱动，是 `dynamic-datasource`
从另一条路把 `ojdbc11` 又带进来了。也就是说这 8.8 MB 是纯搭便车，且与本项目已表达的意图相悖。

想清掉，在 `enlev-spring-boot-starter-mybatis/pom.xml` 的该依赖上加排除：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>dynamic-datasource-spring-boot3-starter</artifactId>
    <exclusions>
        <exclusion>
            <groupId>com.oracle.database.spring</groupId>
            <artifactId>oracle-spring-boot-starter-ucp</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

⚠️ **动手前先确认自动配置会优雅退避**：`dynamic-datasource-spring-boot-common-4.5.0.jar` 里确实有
`DynamicDataSourceCreatorAutoConfiguration$OracleUcp0DataSourceCreatorConfiguration.class`，
`dynamic-datasource-creator-4.5.0.jar` 里有 `OracleUcpDataSourceCreator` / `OracleUcpConfig`。
需先用 `javap -v` 确认该配置类由 `@ConditionalOnClass` 守护（是则排掉 jar 后配置自动失效，
否则启动会 `ClassNotFoundException`）。**本清单只做披露，未擅自改依赖图。**

🔴 **`truffle-enterprise` 是全部 528 个构件里唯一没有宽松分支的 GraalVM 构件。** 来源链：

```
enlev-module-infra（pom.xml:133-137 直接声明）
└─ org.graalvm.polyglot:js:24.1.2（type=pom）
   └─ org.graalvm.js:js:24.1.2（type=pom，聚合器）
      ├─ org.graalvm.js:js-community:24.1.2   ← UPL-1.0 / MIT，宽松
      └─ org.graalvm.truffle:truffle-enterprise:24.1.2   ← 仅 GFTC
```

`org.graalvm.polyglot:js` 与 `org.graalvm.js:js` 这两个聚合 pom 本身声明的是
「GFTC / MIT / UPL-1.0」三许可（可任选，本项目选 UPL-1.0），**但它们把只受 GFTC 约束的
`truffle-enterprise` 拉进了产物**。这是数据接口 JS 后处理脚本引擎的运行时，替换成
`js-community` 会改变用户脚本的执行路径，属需要单独评估与验证的改动，本清单只披露不擅动。

### 5.2 单许可 copyleft（无宽松分支可选，5 项）

| 构件 | 版本 | 协议 | 全文 | 说明 |
| --- | --- | --- | --- | --- |
| `com.mysql:mysql-connector-j` | 9.7.0 | **GPL-2.0 with Universal FOSS Exception v1.0** | [`GPL-2.0-with-Universal-FOSS-Exception-LICENSE`](./licenses/GPL-2.0-with-Universal-FOSS-Exception-LICENSE) | 唯一的强 copyleft。FOSS Exception 允许它与其它开源软件一起分发而不触发传染；本项目以 MIT 发布，**MIT 属该例外认可的 FOSS**，因此组合分发合法。若要把本项目改成非开源许可，这一条会立刻变成阻断项 |
| `com.xkcoding.justauth:justauth-spring-boot-starter` | 1.4.0 | **LGPL-3.0** | [`LGPL-3.0-LICENSE`](./licenses/LGPL-3.0-LICENSE) | 三方登录 starter。作为库依赖使用符合 LGPL 的动态链接语义，但再分发时必须附带协议全文与源码获取途径 |
| `com.xkcoding.http:simple-http` | 1.0.5 | **LGPL-3.0** | 同上 | 上者的 HTTP 层 |
| `com.google.code.findbugs:annotations` | 3.0.1 | **LGPL** | [`LGPL-2.1-LICENSE`](./licenses/LGPL-2.1-LICENSE) | 纯注解库。来源链：`enlev-spring-boot-starter-security` → `bizlog-sdk:3.0.6` → 本构件 |
| `org.aspectj:aspectjweaver` | 1.9.25.1 | **EPL-2.0** | [`EPL-2.0-LICENSE`](./licenses/EPL-2.0-LICENSE) | 弱 copyleft，作用于「模块」粒度；本项目未修改其源码，仅按原样分发 |

### 5.3 多许可里含 copyleft 分支的构件（13 项，本项目均选宽松/弱分支）

| 构件 | 版本 | 可选分支 | **本项目选用** |
| --- | --- | --- | --- |
| `ch.qos.logback:logback-classic` / `logback-core` | 1.5.34 | EPL-2.0 / LGPL-2.1 | **EPL-2.0** |
| `com.github.jsqlparser:jsqlparser` | 5.2 | LGPL-2.1+ / Apache-2.0 | **Apache-2.0** |
| `net.java.dev.jna:jna` | 5.13.0 | LGPL-2.1+ / Apache-2.0 | **Apache-2.0** |
| `net.java.dev.jna:jna-platform` | 5.17.0 | LGPL-2.1+ / Apache-2.0 | **Apache-2.0** |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL-2.0 / GPL-2.0 w/ CPE | **EPL-2.0** |
| `jakarta.mail:jakarta.mail-api` | 2.1.5 | EPL-2.0 / GPL-2.0 w/ CPE / EDL-1.0 | **EDL-1.0**（三者中最宽松） |
| `org.eclipse.angus:angus-mail` | 2.0.5 | 同上 | **EDL-1.0** |
| `org.eclipse.angus:jakarta.mail` | 2.0.5 | 同上 | **EDL-1.0** |
| `org.codelibs:jhighlight` | 1.1.1 | CDDL-1.0 / LGPL-2.1+ | 两条都属弱 copyleft，无宽松分支可选；**按 CDDL-1.0 履行**（文件级 copyleft，本项目未修改其源码）。CDDL-1.0 全文未内置，见 [`licenses/README.md`](./licenses/README.md) |
| `com.github.albfernandez:juniversalchardet` | 2.5.0 | MPL-1.1 / GPL-3.0 / LGPL-3.0 | **LGPL-3.0**（三条中最宽松）。MPL-1.1 全文未内置，见 [`licenses/README.md`](./licenses/README.md) |
| `org.graalvm.js:js` / `org.graalvm.polyglot:js` | 24.1.2 | GFTC / MIT / UPL-1.0 | **UPL-1.0**（但见 §5.1：它们的传递依赖 `truffle-enterprise` 只有 GFTC） |

选宽松分支是双许可/多许可的标准做法，也是各上游项目提供多许可的本意。
`GPL-2.0-with-Classpath-Exception-LICENSE` 与 `GPL-3.0-LICENSE` 两份**未被选用**的分支全文
仍然内置在 `licenses/`，以便审阅者自行核对。

### 5.4 协议未声明（1 项）

| 构件 | 版本 | 情况 |
| --- | --- | --- |
| `net.jcip:jcip-annotations` | 1.0 | 🔴 **pom 里没有 `<licenses>` 标签，jar 里也没有任何协议文件**（实测 jar 只有 4 个注解 class + `MANIFEST.MF`，共 9 个条目、2,254 字节）。pom 只给了 `<url>http://jcip.net/</url>`。上游站点以 CC-BY-2.5 提供这套注解，但**产物本身未作任何声明**，无法从构件自证。来源链：`enlev-server` → `enlev-module-system` → `enlev-spring-boot-starter-security` → `io.github.mouzt:bizlog-sdk:3.0.6` → `com.google.code.findbugs:annotations:3.0.1` → 本构件。**再分发前建议向上游确认，或用 `<exclusion>` 排掉（纯注解，运行期不参与逻辑）。** |

### 5.5 版本错位（同一组件族在产物里存在多个版本）

Maven 的「最近者优先」会让同一组件族的不同构件落在不同版本上。实测三组：

| 组件族 | 实测版本 | 来源 | 风险 |
| --- | --- | --- | --- |
| **Apache POI** | `poi` / `poi-ooxml` / `poi-ooxml-lite` = **5.4.1**；`poi-ooxml-full` / `poi-scratchpad` = **5.5.1** | 5.4.1 ← `enlev-spring-boot-starter-excel` → `fastexcel:1.3.0`；5.5.1 ← `enlev-module-ai` → `spring-ai-tika-document-reader:1.1.8` → `tika-parsers-standard-package:3.3.1` → `tika-parser-microsoft-module:3.3.1` | 🔴 两个问题叠加：① `poi-ooxml-full` 是 `poi-ooxml-lite` 的超集，两者并存 = classpath 上 `org.openxmlformats.schemas.*` 重复类；② schema jar 5.5.1 配 core 5.4.1，Apache POI 不支持跨版本混用，解析 OOXML 时可能 `NoSuchMethodError`。合计多占约 20 MB |
| **JNA** | `jna` = **5.13.0**；`jna-platform` = **5.17.0** | 均来自 `enlev-module-ai` → `spring-ai-starter-model-azure-openai:1.1.8` → `azure-identity:1.18.2`（`jna` 再经 `msal4j-persistence-extension:1.3.0`） | `jna-platform` 与 `jna` 主版本必须一致，错位可能导致 `UnsatisfiedLinkError`。仅在 Azure 身份凭据的本地缓存路径上触发 |
| **Bouncy Castle** | `bcprov` / `bcpkix` / `bcutil` = **1.80**；`bcjmail` = **1.84** | 1.80 ← `weixin-java-miniapp:4.8.4-20260623.211820` → `bcpkix`；1.84 ← `tika-parser-crypto-module:3.3.1` | BC 各构件跨小版本混用一般可用，但 `bcjmail` 1.84 调用的 `bcprov` API 若不在 1.80 里，会在解析加密邮件时抛 `NoSuchMethodError` |

这三组都不是本清单能解决的问题，属依赖治理，已单独跟踪。**本清单如实披露现状。**

### 5.6 产物里的可疑内容（打包卫生）

| 现象 | 实测 | 说明 |
| --- | --- | --- |
| `lombok-1.18.46.jar`（2,045,364 字节）出现在 `BOOT-INF/lib` | 是 | lombok 是编译期注解处理器，运行期完全不需要。本项目根 pom **没有** `spring-boot-starter-parent`，因此拿不到它对 lombok 的默认 `<exclude>`。对照：`mapstruct-processor` 与 `spring-boot-configuration-processor` **确实没有**进入产物（实测为 0），说明插件排掉了那两个、漏了 lombok |
| `mapstruct-jdk8-1.6.3.jar`（1,658 字节）出现在 `BOOT-INF/lib` | 是 | 这是 MapStruct 给 **Java 8** 用的兼容壳，本项目 `java.version=17`，是死重 |
| lombok 版本双写不一致 | `pom.xml:37` = **1.18.42**（`annotationProcessorPaths` 用）；`enlev-dependencies/pom.xml:58` = **1.18.46**（依赖管理用） | 导入的 BOM 的属性不会覆盖导入方的属性，所以**编译期跑的注解处理器是 1.18.42、落到产物里的是 1.18.46**。两处都合法但明显是漏改 |
| `wx-java` 全家解析成带时间戳的 SNAPSHOT | 实测 **5 个** jar：`wx-java-mp-spring-boot-starter`、`wx-java-miniapp-spring-boot-starter`、`weixin-java-mp`、`weixin-java-miniapp`、`weixin-java-common`，版本一律 `4.8.4-20260623.211820` | 时间戳后缀意味着 pom 里写的是 `4.8.4-SNAPSHOT`。构建不可复现（上游重新 deploy 后本地/CI 拿到的字节码会变），离线构建也可能在快照过期后失败，且无法在本清单里锁定确定版本 |
| fat jar 合计 **325 MB** / 531 个 jar | 是 | 最大的几项：`js-language` 26 MB、`icu4j` 17 MB、`poi-ooxml-full` 14 MB、`truffle-api` 12 MB、`grpc-netty-shaded` 9.7 MB、`captcha` 9.1 MB、`byte-buddy` 9.0 MB、`bcprov` 8.7 MB、`groovy` 7.7 MB、`ojdbc11` 7.3 MB |

以上均已记录为待修项，**本清单不擅自改依赖图**：改 scope / 加 exclusion 会影响每个模块的编译
classpath 与自动配置的退避行为，必须重新打包并启动验证，属独立改动。

### 5.7 需要说明的两个「看着像但其实不是」

- **Hutool 不是 MPL-2.0。** 产物里同时存在两套 Hutool：`cn.hutool:hutool-all:5.8.46`
  的 pom 声明 **木兰宽松许可证 第 2 版（MulanPSL-2.0）**，`org.dromara.hutool:*:6.0.0-M22`
  （`hutool-core` / `hutool-extra` / `hutool-log` / `hutool-setting` / `hutool-swing`）声明
  **Apache-2.0**。MulanPSL-2.0 是宽松协议（已通过 OSI 认证），**不是** copyleft，
  与 MIT 组合分发无碍。两套并存本身是版本治理问题，不是协议问题。
- **`netty-tcnative-boringssl-static` 的 pom 只写 Apache-2.0，但它打包的是 BoringSSL 原生库。**
  BoringSSL 采用 ISC + OpenSSL 双许可（宽松）。这是 pom 声明与实际内含物的口径差，
  为免审阅者困惑在此点明；5 个平台 classifier 变体同理。

---

## 6. 声明了但不随产物分发

这些在本仓 pom 里能搜到，但**不在 `enlev-server.jar` 里**，因此不构成再分发义务。
点名是为了防止有人看到 pom 就以为它们随包发出去了。

### 6.1 `<optional>true</optional>` 的数据库驱动（7 项，全部不落产物）

`enlev-spring-boot-starter-mybatis/pom.xml` 第 36-70 行把它们全声明为 `<optional>true</optional>`，
Maven 的 optional 依赖**不具备传递性**，因此不会进入 `enlev-server` 的 fat jar（实测 `BOOT-INF/lib`
内 0 命中）。版本与协议均实测：版本取自 `mvn dependency:list -pl enlev-framework/enlev-spring-boot-starter-mybatis`，
协议取自本地 Maven 仓库里**该版本**的 pom `<licenses>`。

| 构件 | 实测解析版本 | 协议（pom 声明） |
| --- | --- | --- |
| `com.oracle.database.jdbc:ojdbc8` | 23.7.0.25.01 | 🔴 Oracle FUTC（见 §5.1：项目主动把它设为 optional，但 `ojdbc11` 又从 dynamic-datasource 传递进来了） |
| `org.postgresql:postgresql` | 42.7.11 | BSD-2-Clause |
| `com.microsoft.sqlserver:mssql-jdbc` | 12.10.2.jre11 | MIT |
| `com.dameng:DmJdbcDriver18`（达梦 DM8） | 8.1.3.140 | Apache-2.0 |
| `cn.com.kingbase:kingbase8`（人大金仓） | 9.0.1.jre7 | Apache-2.0 |
| `org.opengauss:opengauss-jdbc` | 7.0.0-RC3-og | BSD-2-Clause |
| `com.taosdata.jdbc:taos-jdbcdriver`（涛思 TDengine） | 3.8.4 | MIT |

> ⚠️ 达梦与金仓的驱动在各自 pom 里声明的是 **Apache-2.0**（达梦发布在
> <https://gitee.com/dmedu/dm-jdbc-jars>、金仓在 <https://gitee.com/kingbasees/kingbase8>），
> 不是「厂商专有协议」——网上常见的说法与 pom 实测不符，本清单以 pom 为准。
> 但这只是**构件自带的声明**；若企业采购合同里另有 EULA 约束，以合同为准。

只有 `mysql-connector-j` 是**非 optional** 的（同文件第 32-35 行），因此它是唯一真正随包分发的
数据库驱动——也正是 §5.2 里那条 GPL-2.0 + FOSS Exception。

### 6.2 只在 `dependencyManagement` 里的构件

`enlev-dependencies/pom.xml` 用 `<dependencyManagement>` 统一管理版本，**只做版本声明、不引入依赖**。
其中最容易误会的是积木报表：

- `org.jeecgframework.jimureport:jimureport-spring-boot-starter`
- `org.jeecgframework.jimureport:jimubi-spring-boot-starter`

两者在 `enlev-dependencies/pom.xml` 第 84-85 行有版本属性、第 767-780 行有条目，
但**没有任何模块实际 `<dependency>` 它们**，实测 `BOOT-INF/lib` 内 0 命中。
🔴 这一点很重要：积木报表是**免费但非开源**的商业组件，若将来真的引入，
其授权条款与本项目 MIT 发布方式**不兼容**，需单独取得商业授权。

### 6.3 `provided` / `test` 作用域

`dependency:list -DincludeScope=runtime` 已排除 provided 与 test，因此本清单不含它们。
典型如 `springdoc-openapi-starter-webmvc-ui` 在 `enlev-common/pom.xml:62-64` 被显式标为
`provided`（注释写明「主要是 PageParam 使用到」）。

---

## 7. 传递依赖口径

- 528 个坐标里，**绝大多数是传递依赖**：本仓 pom 直接声明的只有几十项，其余由 Spring Boot /
  Spring AI / Flowable / Tika / AWS SDK 等自身引入。
- **实测无 AGPL / SSPL 构件**，也没有任何「网络著佐权」阻断项。
- 唯一的强 copyleft 是 `mysql-connector-j`（GPL-2.0 + Universal FOSS Exception），
  该例外明确覆盖本项目的 MIT 发布方式，见 §5.2。
- 弱 copyleft（LGPL / EPL / CDDL / MPL）共 22 项，其中 13 项有多许可可选、本项目已选宽松分支（§5.3），
  5 项无宽松分支（§5.2），其余为双许可组合。
  本项目**未修改**这些组件的源码，只按原样以库的形式使用与分发——这满足 LGPL 的动态链接语义、
  EPL 的模块边界与 CDDL/MPL 的文件级 copyleft。
- 上游组件各自的传递依赖由上游负责声明，本清单以**实测解析结果**为准，
  不逐条追溯每个坐标的引入路径（§5 里点名的例外都给了完整链路）。

---

## 8. MIT 兼容性声明

本项目以 **MIT** 协议发布（见 [`LICENSE`](./LICENSE)，© 2026 启凌），但
**MIT 授权只覆盖本项目自己的代码，不覆盖上述第三方组件**。第三方组件一律按其原始协议授权：

- **MIT / MIT-0 / BSD / ISC / 0BSD / CC0 / 公有领域 / Apache-2.0 / UPL-1.0 / EDL-1.0 /
  MulanPSL-2.0 / Unicode-ICU / Indiana-IUxSL / JDOM / Bouncy Castle 组件**：
  保留其版权行与许可声明即可，本清单已履行该义务；
- **LGPL / EPL / CDDL / MPL 组件**：本清单履行「附带协议全文 + 声明未修改源码 + 提供源码获取途径」
  的义务；协议全文在 [`licenses/`](./licenses)，源码即各上游公开仓库；
- **GPL-2.0 + Universal FOSS Exception（`mysql-connector-j`）**：该例外允许与 FOSS 组合分发，
  本项目 MIT 属其认可范围；
- 🔴 **Oracle FUTC / GraalVM GFTC / UnRar License / 未声明协议的 `jcip-annotations`**：
  这些**不受 MIT 覆盖、也不是开源协议**。分发本项目产物即等于分发它们，
  请自行阅读 §5 与 `licenses/` 里的全文并确认可分发；
- 任何再分发（含二次开发后对外发布）都应连同本文件与 `licenses/` 目录一起分发。

---

## 9. 产品整体：三仓各自的清单互不覆盖

见 §1 的三仓表。**每仓清单只对本仓产物负责**，因此：

- `Vue`、`Element Plus`、`Vite`、`Pinia`、`UnoCSS`、`bpmn-js`、`ECharts`、`Monaco Editor`、
  `form-create`、`form-create-designer` **不出现在本清单**——本仓不含前端代码、不打包前端产物，
  它们在 Web 仓 `ainb-ui-admin-vue3/THIRD-PARTY-NOTICES.md`；
- `unibest`、`uni-app`、`@wot-ui/ui`、`z-paging` 同理，在移动端仓
  `ainb-ui-admin-uniapp/THIRD-PARTY-NOTICES.md`；
- 三份清单**内容不同是应当的**，不要互相对齐成一样。

---

## 10. 本项目自身构件不是第三方软件

打进 `BOOT-INF/lib` 的 19 个 `cn.enlev.ainb:enlev-*` jar 是本项目自己的模块，
由本仓 `LICENSE` 以 MIT 授权，**不属于第三方软件**，不出现在上述任何清单中：

```
enlev-common                                          （1）
enlev-spring-boot-starter-{web,security,mybatis,redis,mq,job,monitor,
                           protection,websocket,excel,
                           biz-ip,biz-tenant,biz-data-permission}   （13）
enlev-module-{system,infra,bpm,ai,agent}              （5）
```

第 20 个模块 `enlev-server` 是外层可执行 jar 本身，不在 `BOOT-INF/lib` 里。

同理：上游 `yudao` 的包名与署名在本仓中部分保留（历史遗留），
**不要**按改名后的 `enlev-*` / `ainb-*` 名字把本项目构件反向补进第三方清单。

`enlev-module-agent` 是本项目**自研**模块（工具体系、Agent loop、Plan 预览确认、权限网关），
上游 ruoyi-vue-pro 没有对应物，其代码同样由本仓 MIT 授权覆盖。

---

## 11. 本清单怎么来的（复现方式）

所有数值可复现，**不靠记忆、不抄上游文档**：

```bash
cd ainb

# 1. 解析产物实际用到的依赖坐标（compile + runtime）
mvn -o -q dependency:list -DincludeScope=runtime -DappendOutput=true -Dsort=true \
    -DoutputFile=../_logs/backend-deps.txt          # → 528 个第三方坐标

# 2. 逐个读 C:\maven_repo 里对应 pom 的 <licenses>，缺失时向上递归 <parent>
#    归并出 §3 的协议分布与 §5 的逐条点名

# 3. 与真正的产物对账（jar 才是权威）
unzip -Z1 enlev-server/target/enlev-server.jar "BOOT-INF/lib/*"   # → 531 个 jar

# 4. 追引入路径（§5 里的每条来源链都是这么打出来的）
mvn -o dependency:tree -Dincludes='org.apache.poi,net.java.dev.jna,org.bouncycastle' \
    -DoutputFile=../_logs/tree.txt
mvn -o dependency:tree -Dincludes=net.jcip:jcip-annotations -DoutputFile=../_logs/jcip.txt
mvn -o dependency:tree -Dincludes='org.graalvm.truffle:truffle-enterprise,com.oracle.database.jdbc:ojdbc11' \
    -DoutputFile=../_logs/graal.txt

# 5. 确认 optional / dependencyManagement-only 的构件确实不落产物
unzip -Z1 enlev-server/target/enlev-server.jar "BOOT-INF/lib/*" | grep -iE "dm8|kingbase|opengauss|taos|jimureport|mssql|postgresql"
```

中间产物写在 `C:\code\xpy\_logs\`，**不入库**（该目录另有含不可信载荷的后端日志，见工作区 `AGENTS.md`）。
