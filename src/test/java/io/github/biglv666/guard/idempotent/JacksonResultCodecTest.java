package io.github.biglv666.guard.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonResultCodecTest {

    @Test
    void serializesAndDeserializesJavaTimeReturnType() throws Exception {
        JacksonResultCodec codec = new JacksonResultCodec();
        LocalDateTime expected = LocalDateTime.of(2026, 9, 20, 12, 34, 56);

        String payload = codec.serialize(expected);
        Type returnType = JacksonResultCodecTest.class
                .getDeclaredMethod("localDateTimeResult")
                .getGenericReturnType();

        assertEquals(expected, codec.deserialize(payload, returnType));
    }

    @Test
    void toleratesUnknownFieldsForRollingDeployReplay() throws Exception {
        JacksonResultCodec codec = new JacksonResultCodec();
        // 模拟滚动部署：窗口内的旧结果 payload 含部署后已从返回类型中移除的字段，
        // 重放应容忍未知字段（新增字段的场景同理）
        String stalePayload = new ObjectMapper()
                .writeValueAsString(new OldSchemaResult("order-1", 7, "legacy-field"));
        Type returnType = JacksonResultCodecTest.class
                .getDeclaredMethod("newSchemaResult")
                .getGenericReturnType();

        Object replayed = codec.deserialize(stalePayload, returnType);
        assertEquals(new NewSchemaResult("order-1", 7), replayed);
    }

    private LocalDateTime localDateTimeResult() {
        return null;
    }

    /** 新返回类型的占位方法：仅为获取泛型返回类型。 */
    private NewSchemaResult newSchemaResult() {
        return null;
    }

    /** 部署前的旧返回类型（含部署后将移除的字段）。 */
    static class OldSchemaResult {
        public String orderId;
        public int seq;
        public String legacyField;

        OldSchemaResult(String orderId, int seq, String legacyField) {
            this.orderId = orderId;
            this.seq = seq;
            this.legacyField = legacyField;
        }
    }

    /** 部署后的新返回类型（不含 legacyField，equals/hashCode 只比较存量字段）。 */
    static class NewSchemaResult {
        public String orderId;
        public int seq;

        /** Jackson 反序列化需要的默认构造器。 */
        public NewSchemaResult() {
        }

        NewSchemaResult(String orderId, int seq) {
            this.orderId = orderId;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NewSchemaResult that
                    && seq == that.seq && orderId.equals(that.orderId);
        }

        @Override
        public int hashCode() {
            return 31 * orderId.hashCode() + seq;
        }
    }
}
