package io.github.ggfto.atlas.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** JSON dos frames: tipo no campo {@code type}, Instant/Duration em ISO-8601, propriedade desconhecida recusa. */
public final class FrameCodec {

    /** Teto de um frame (um snapshot de Docker/k8s de uma maquina cabe com folga). */
    public static final int MAX_FRAME_CHARS = 4 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private FrameCodec() {
    }

    public static String encode(Frame frame) {
        try {
            return JSON.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("frame nao serializavel: " + frame.getClass().getSimpleName(), e);
        }
    }

    public static Frame decode(String text) {
        if (text.length() > MAX_FRAME_CHARS) {
            throw new ProtocolException("frame grande demais: " + text.length() + " > " + MAX_FRAME_CHARS);
        }
        try {
            return JSON.readValue(text, Frame.class);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("frame invalido: " + e.getOriginalMessage(), e);
        }
    }
}
