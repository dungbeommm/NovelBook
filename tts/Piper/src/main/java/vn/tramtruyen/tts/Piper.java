package vn.tramtruyen.tts;

import java.util.Arrays;

public final class Piper {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "piper", "Piper", "vmodel", 22050,
            Arrays.asList("libncnn.so", "libpipertts.so"),
            Arrays.asList(), true
    );

    private Piper() {}
}
