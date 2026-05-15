package org.springframework.data.redis.laboratory.l4.l4_09.toushi;

import java.util.ArrayList;
import java.util.List;

/**
 * 偷师 Demo 1：参数对象 + Builder。
 * <p>
 * 灵感来源：{@code ScanOptions} 把 MATCH / COUNT / TYPE 收口成 immutable 参数对象。
 * <p>
 * 业务里凡是"查询条件 ≥ 4 个 / 还会继续加"的场景，都该提一个 *Options 对象，
 * 而不是把方法签名改成 {@code findUsers(String city, Integer age, Boolean vip, ...)}。
 * <p>
 * 茶饮 C 端真实场景：
 * <ul>
 *   <li>门店搜索（地理范围 + 营业状态 + 排序 + 分页）；</li>
 *   <li>优惠券查询（适用品类 + 用户分群 + 时间窗口）；</li>
 *   <li>用户画像查询（标签集 + 时间窗 + 数据源版本）。</li>
 * </ul>
 */
public class QueryOptionsDesignDemo {

    /** 业务参数对象——immutable，所有 setter 仅在 builder 内可见。 */
    public static final class UserQueryOptions {
        private final String city;
        private final Integer minAge;
        private final Boolean vip;
        private final List<String> tags;
        private final long pageSize;

        private UserQueryOptions(Builder b) {
            this.city = b.city;
            this.minAge = b.minAge;
            this.vip = b.vip;
            this.tags = List.copyOf(b.tags);
            this.pageSize = b.pageSize;
        }

        public String getCity()      { return city; }
        public Integer getMinAge()   { return minAge; }
        public Boolean getVip()      { return vip; }
        public List<String> getTags(){ return tags; }
        public long getPageSize()    { return pageSize; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String city;
            private Integer minAge;
            private Boolean vip;
            private final List<String> tags = new ArrayList<>();
            private long pageSize = 50;

            public Builder city(String c)         { this.city = c; return this; }
            public Builder minAge(Integer age)    { this.minAge = age; return this; }
            public Builder vip(Boolean vip)       { this.vip = vip; return this; }
            public Builder addTag(String tag)     { this.tags.add(tag); return this; }
            public Builder pageSize(long size)    {
                if (size <= 0 || size > 1000) {
                    throw new IllegalArgumentException("pageSize 必须 1~1000：" + size);
                }
                this.pageSize = size; return this;
            }
            public UserQueryOptions build()       { return new UserQueryOptions(this); }
        }
    }

    public static void main(String[] args) {
        UserQueryOptions opts = UserQueryOptions.builder()
                .city("Shanghai")
                .minAge(18)
                .vip(Boolean.TRUE)
                .addTag("milk-tea")
                .addTag("coffee")
                .pageSize(100)
                .build();

        System.out.println("UserQueryOptions: city=" + opts.getCity()
                + ", minAge=" + opts.getMinAge()
                + ", vip=" + opts.getVip()
                + ", tags=" + opts.getTags()
                + ", pageSize=" + opts.getPageSize());

        // 偷师对照：ScanOptions.scanOptions().match(...).count(...).build()
        // 同样的"参数对象 + builder"模式——签名稳定、加字段不破坏调用方。
    }
}
