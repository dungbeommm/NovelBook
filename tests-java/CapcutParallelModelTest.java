import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mô phỏng đa luồng đúng model mới của CapcutTtsProvider:
 * Semaphore(3) cho task song song + throttle submit toàn cục trong mutex.
 * So sánh với model cũ: Mutex(1) tuần tự tuyệt đối.
 */
public class CapcutParallelModelTest {
    static final int TASKS = 9;
    static final int TASK_TIME_MS = 800;  // giả lập submit+poll+download 1 câu
    static final long THROTTLE_MS = 300;  // giả lập MIN_SUBMIT_INTERVAL (bản thật 600ms)

    static class Engine {
        final Semaphore semaphore;
        final Object throttleLock = new Object();
        long lastSubmit = 0;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Long> submitTimes = Collections.synchronizedList(new ArrayList<>());

        Engine(int permits) { semaphore = new Semaphore(permits); }

        void runTask() throws InterruptedException {
            semaphore.acquire();
            try {
                // Throttle: delay TRONG lock để giữ spacing toàn cục giữa các lần
                // submit — tương đương throttleMutex.withLock { ... } trong Kotlin.
                synchronized (throttleLock) {
                    long now = System.currentTimeMillis();
                    long remaining = THROTTLE_MS - (now - lastSubmit);
                    if (lastSubmit > 0 && remaining > 0) Thread.sleep(remaining);
                    lastSubmit = System.currentTimeMillis();
                    submitTimes.add(lastSubmit);
                }
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                Thread.sleep(TASK_TIME_MS); // server xử lý + poll + tải audio
                inFlight.decrementAndGet();
            } finally {
                semaphore.release();
            }
        }
    }

    static long run(Engine engine) throws InterruptedException {
        long start = System.currentTimeMillis();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            Thread t = new Thread(() -> {
                try { engine.runTask(); } catch (InterruptedException ignored) {}
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        return System.currentTimeMillis() - start;
    }

    public static void main(String[] args) throws Exception {
        Engine serial = new Engine(1);   // model cũ: Mutex tuần tự
        long serialTime = run(serial);

        Engine parallel = new Engine(3); // model mới: Semaphore(3)
        long parallelTime = run(parallel);

        int failed = 0;
        System.out.println("Model cu (Mutex)     : " + serialTime + "ms, max in-flight = " + serial.maxInFlight);
        System.out.println("Model moi (Semaphore3): " + parallelTime + "ms, max in-flight = " + parallel.maxInFlight);

        if (parallel.maxInFlight.get() <= 3) System.out.println("PASS  khong bao gio qua 3 task dong thoi");
        else { System.out.println("FAIL  vuot qua 3 task: " + parallel.maxInFlight.get()); failed++; }

        boolean spacingOk = true;
        List<Long> times = parallel.submitTimes;
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) - times.get(i - 1) < THROTTLE_MS - 25) spacingOk = false; // 25ms dung sai clock
        }
        if (spacingOk) System.out.println("PASS  nhip submit toan cuc >= " + THROTTLE_MS + "ms (chong bi CapCut chan)");
        else { System.out.println("FAIL  submit sat nhau: " + times); failed++; }

        if (parallelTime < serialTime * 0.6)
            System.out.println("PASS  song song nhanh hon ro: " + parallelTime + "ms vs " + serialTime + "ms (x"
                    + String.format(java.util.Locale.US, "%.1f", (double) serialTime / parallelTime) + ")");
        else { System.out.println("FAIL  cai thien khong ro: " + parallelTime + " vs " + serialTime); failed++; }

        if (failed > 0) System.exit(1);
        System.out.println("ALL PASS");
    }
}
