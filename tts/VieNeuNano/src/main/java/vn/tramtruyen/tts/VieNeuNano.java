package vn.tramtruyen.tts;

import java.util.Arrays;

public final class VieNeuNano {
    public static final VoiceEngineModule ENGINE = new VoiceEngineModule(
            "vieneu_nano", "VieNeu Nano", "mnn", 24000,
            Arrays.asList("libMNN.so", "libvieneunano.so"),
            Arrays.asList("text_encoder.mnn", "duration_predictor.mnn", "vector_estimator.mnn", "codec_decoder.mnn"), true
    );

    private VieNeuNano() {}
}
