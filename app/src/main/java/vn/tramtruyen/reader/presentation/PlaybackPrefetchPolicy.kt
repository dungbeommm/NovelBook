package vn.tramtruyen.reader.presentation

/**
 * Quyết định prefetch thuần Kotlin, tách khỏi Android để kiểm thử hồi quy.
 *
 * Trước đây CapCut bị tuần tự hóa hoàn toàn vì provider chỉ cho một task tại
 * một thời điểm. Provider giờ cho phép MAX_CONCURRENT_TASKS=3 task song song
 * (semaphore) kèm throttle submit toàn cục, nên mọi provider đều được prefetch
 * song song; CapCut chỉ khác ở độ sâu cửa sổ và vẫn warm câu đầu chương kế.
 */
internal object PlaybackPrefetchPolicy {

    /**
     * Stagger giữa các prefetch: câu đang phát (feeder gọi synth trực tiếp)
     * giành lane trước, prefetch không tạo backlog chắn đầu như lỗi trước đây.
     */
    const val PREFETCH_STAGGER_MS = 150L

    fun isSerializedProvider(voiceId: String): Boolean =
        voiceId.startsWith("capcut:", ignoreCase = true)

    /**
     * Độ sâu cửa sổ prefetch lúc vào chương. CapCut khớp đúng số lane song song
     * của provider (3); các provider khác giữ nguyên hành vi cũ (4).
     */
    fun parallelPrefetchCount(voiceId: String): Int =
        if (isSerializedProvider(voiceId)) 3 else 4

    /** Warm câu đầu chương kế vẫn chỉ áp dụng cho CapCut (provider cloud chậm nhất). */
    fun shouldWarmSerializedNext(
        voiceId: String,
        queued: Int,
        queueDepth: Int,
        hasNext: Boolean,
        alreadyStarted: Boolean,
    ): Boolean = isSerializedProvider(voiceId) &&
        hasNext && !alreadyStarted && queueDepth > 0 && queued >= queueDepth
}
