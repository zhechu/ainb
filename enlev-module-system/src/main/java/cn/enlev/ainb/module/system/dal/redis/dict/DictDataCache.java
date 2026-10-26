package cn.enlev.ainb.module.system.dal.redis.dict;

import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 字典缓存条目：一份字典数据 + 产生它的那个版本号
 *
 * <h3>为什么版本号必须和数据绑在一起</h3>
 * 版本号的用途是让前端判断「我手上这份还新不新鲜」。如果版本号和数据是分别取的两个值，
 * 就存在这样的交错：某个请求读到版本 7 后开始查库，查库期间字典被改动（版本变成 8），
 * 该请求最后把「版本 8 + 版本 7 时代的数据」返回给前端。前端存下这一对之后，
 * 之后每次比对版本都相等，于是永远不会再刷新——错误数据被永久固化。
 * <p>
 * 把两者放进同一个对象、同一次读写，就从结构上消灭了这种错配：
 * 数据只要是旧的，它携带的版本号也一定是旧的，前端下次比对必然发现不一致并自愈。
 *
 * @author 走进新时代
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictDataCache {

    /**
     * 产生这份数据时的字典版本号
     */
    private Long version;

    /**
     * 全量启用的字典数据
     */
    private List<DictDataDO> list;

}
