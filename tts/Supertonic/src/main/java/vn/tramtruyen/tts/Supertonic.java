package vn.tramtruyen.tts;

import java.util.Arrays;

public final class Supertonic {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "supertonic", "Supertonic", "mnn", 44100,
            Arrays.asList("libMNN.so", "libsupertonic.so"),
            Arrays.asList("text_encoder.mnn", "duration_predictor.mnn", "vector_estimator.mnn", "vocoder.mnn", "unicode_indexer.bin", "voices.meta.json"), false
    );

    private Supertonic() {}
}
