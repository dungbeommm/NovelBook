package vn.tramtruyen.tts;

import java.util.Arrays;

public final class VieNeu {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "vieneu", "VieNeu", "gguf", 48000,
            Arrays.asList("libaudiocpp.so", "libvieneutts.so"),
            Arrays.asList(), true
    );

    private VieNeu() {}
}
