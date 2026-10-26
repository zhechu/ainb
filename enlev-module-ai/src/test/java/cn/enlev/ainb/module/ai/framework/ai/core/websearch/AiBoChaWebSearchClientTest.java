package cn.enlev.ainb.module.ai.framework.ai.core.websearch;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.ai.framework.ai.core.VendorApiKeys;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchRequest;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchResponse;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.bocha.AiBoChaWebSearchClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * {@link AiBoChaWebSearchClient} 集成测试类
 *
 * @author 芋道源码
 */
public class AiBoChaWebSearchClientTest {

    private final AiBoChaWebSearchClient webSearchClient = new AiBoChaWebSearchClient(
            VendorApiKeys.of("BOCHA_API_KEY"));

    @Test
    @Disabled
    public void testSearch() {
        AiWebSearchRequest request = new AiWebSearchRequest()
                .setQuery("阿里巴巴")
                .setCount(3);
        AiWebSearchResponse response = webSearchClient.search(request);
        System.out.println(JsonUtils.toJsonPrettyString(response));
    }

}