package vn.tramtruyen.tts;

import java.util.Arrays;

public final class ViFast {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "vifast", "ViFast", "vmodel", 24000,
            Arrays.asList("libMNN.so", "libfasttts.so"),
            Arrays.asList(), true
    );

    private ViFast() {}
}
