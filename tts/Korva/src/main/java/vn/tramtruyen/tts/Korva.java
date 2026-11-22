package vn.tramtruyen.tts;

import java.util.Arrays;

public final class Korva {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "korva", "Korva", "mnn", 44100,
            Arrays.asList("libMNN.so", "libkorva.so"),
            Arrays.asList("text_encoder.mnn", "duration_predictor.mnn", "ve_branch.mnn", "ve_prefix.mnn", "ve_uncond.bin", "vocoder.mnn", "unicode_indexer.bin", "voices.meta.json"), false
    );

    private Korva() {}
}
