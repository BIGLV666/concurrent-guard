package io.github.biglv666.guard.idempotent;

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

    private LocalDateTime localDateTimeResult() {
        return null;
    }
}
