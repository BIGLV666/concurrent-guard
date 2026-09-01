package io.github.biglv666.guard.idempotent;

import java.lang.reflect.Type;

/**
 * 幂等结果编解码器 SPI —— {@link IdempotentMode#REPLAY} 重放模式下，
 * 负责业务返回值与占位存储内容（字符串）之间的双向转换。
 *
 * <p>默认实现为 {@link JacksonResultCodec}（宿主 classpath 存在 Jackson 时自动装配）；
 * 业务方可注册自定义 {@link ResultCodec} Bean 覆盖，例如改用其他序列化框架或增加加密。
 *
 * @author Guard Team
 * @since 0.2.0
 */
public interface ResultCodec {

    /**
     * 将业务方法返回值序列化为可存储的字符串。
     *
     * <p>void 方法返回 null 时由实现序列化为固定字面量（如 JSON 的 {@code "null"}），
     * 保证"结果已保存"与"结果未保存（仍在处理中）"可区分。
     *
     * @param result 业务方法返回值，可能为 null
     * @return 序列化后的内容，非 null
     * @throws Exception 序列化失败（如返回值不可序列化）；切面仅记录告警，
     *                   结果不落库时窗口内重复请求退回拒绝行为
     */
    String serialize(Object result) throws Exception;

    /**
     * 将存储内容反序列化为业务返回值。
     *
     * @param payload    {@link #serialize} 保存的内容
     * @param returnType 被拦截方法的泛型返回类型，用于还原具体类型
     * @return 重放给调用方的返回值；payload 表示"结果为 null"时返回 null
     * @throws Exception 反序列化失败；切面按一致性优先原则拒绝该请求并发布降级事件
     */
    Object deserialize(String payload, Type returnType) throws Exception;
}
