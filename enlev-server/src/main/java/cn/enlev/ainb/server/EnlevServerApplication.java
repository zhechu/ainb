package cn.enlev.ainb.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 *
 * 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
 * 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
 * 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
 *
 * @author 芋道源码
 */
@SuppressWarnings("SpringComponentScan") // 忽略 IDEA 无法识别 ${enlev.info.base-package}
@SpringBootApplication(scanBasePackages = {"${enlev.info.base-package}.server", "${enlev.info.base-package}.module"})
public class EnlevServerApplication {

    public static void main(String[] args) {
        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章

        SpringApplication.run(EnlevServerApplication.class, args);
//        new SpringApplicationBuilder(EnlevServerApplication.class)
//                .applicationStartup(new BufferingApplicationStartup(20480))
//                .run(args);

        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
        // 如果你碰到启动的问题，请认真阅读 https://doc.enlev.cn/quick-start/ 文章
    }

}
