package io.github.biglv666.guard.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;

/**
 * 默认结果编解码器 —— 基于 Jackson {@link ObjectMapper}。
 *
 * <p>null 返回值序列化为 JSON 字面量 {@code "null"}，反序列化时还原为 null，
 * 与"结果未保存"（占位键中仍是处理中标记）天然可区分；
 * 反序列化按方法泛型返回类型还原，保证集合、POJO 等复杂类型的重放类型正确。
 *
 * @author Guard Team
 * @since 0.2.0
 */
public class JacksonResultCodec implements ResultCodec {

    /**
     * 独立 ObjectMapper：不与宿主应用共享配置（如 Spring Boot 定制的模块注册），
     * 避免宿主对业务对象的定制序列化行为影响重放链路的确定性。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String serialize(Object result) throws Exception {
        return objectMapper.writeValueAsString(result);
    }

    @Override
    public Object deserialize(String payload, Type returnType) throws Exception {
        return objectMapper.readValue(payload, objectMapper.getTypeFactory().constructType(returnType));
    }
}
