package vn.tramtruyen.tts;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public record VoiceEngineModule(
        String id, String name, String format, int sampleRate,
        List<String> libraries, List<String> modelFiles, boolean usesCommonData
) {
    public VoiceEngineModule {
        libraries = Collections.unmodifiableList(new ArrayList<>(libraries));
        modelFiles = Collections.unmodifiableList(new ArrayList<>(modelFiles));
    }

    public Enum<?> runtimeType(ClassLoader loader) throws ClassNotFoundException {
        Class<?> type = loader.loadClass("ap");
        String enumName = id.toUpperCase(Locale.ROOT);
        for (Object constant : type.getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            if (enumName.equals(value.name())) return value;
        }
        throw new IllegalStateException("VBook 1.2.7 is missing engine " + name);
    }

    public Object createEngine(ClassLoader loader, Enum<?> type) throws ReflectiveOperationException {
        return loader.loadClass("use").getMethod("h", type.getDeclaringClass()).invoke(null, type);
    }

    public boolean accepts(String declaredFormat) {
        return format.equalsIgnoreCase(declaredFormat);
    }

    public boolean hasModelFiles(File directory) {
        if (!new File(directory, "config.json").isFile()) return false;
        if (!modelFiles.isEmpty()) {
            return modelFiles.stream().allMatch(path -> isNonEmptyFile(new File(directory, path)));
        }
        File[] weights = directory.listFiles(file ->
                isNonEmptyFile(file) && file.getName().endsWith("." + format));
        return weights != null && weights.length != 0;
    }

    private static boolean isNonEmptyFile(File file) {
        return file.isFile() && file.length() > 0;
    }
}
